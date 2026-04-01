package com.impala.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrderRepositoryTest {
    private val session = UserSession(
        userId = "courier-42",
        role = UserRole.COURIER,
        authToken = "secure-token-1234"
    )
    private val clientSession = UserSession(
        userId = "client-1",
        role = UserRole.CLIENT,
        authToken = "secure-token-client"
    )

    private fun baseOrder(orderNumber: String = "IMP-1001"): DeliveryOrder =
        DeliveryOrder(
            orderNumber = orderNumber,
            packageType = "Carton",
            weightKg = 3.5,
            volumeM3 = 0.20,
            collectionLocation = "Kinshasa Gombe",
            deliveryAddress = "Limete 7e rue",
            recipientPhoneNumber = "+243900000000",
            deliveryType = DeliveryType.NORMAL,
            packageValue = 250.0
        )

    @Test
    fun registerOrder_online_callsDolibarrAndNotifiesCouriers() {
        val api = InMemoryDolibarrApi()
        val connectivity = InMemoryConnectivityMonitor(true)
        val pushNotifier = InMemoryPushNotifier()
        val realtime = InMemoryRealtimeEventBus()
        val whatsAppNotifier = InMemoryWhatsAppNotifier()
        val repository = OrderRepository(
            api = api,
            workflow = OrderWorkflow(),
            connectivity = connectivity,
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = pushNotifier,
            realtimeEventBus = realtime,
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(setOf("AM-OK")),
            whatsAppNotifier = whatsAppNotifier
        )

        val created = repository.registerOrder(clientSession, baseOrder())

        assertEquals(OrderStatus.PENDING, created.status)
        assertEquals(1, pushNotifier.notifications.size)
        assertTrue(pushNotifier.notifications.first().contains("NEW_ORDER"))
        assertEquals(1, realtime.events.size)
    }

    @Test
    fun registerOrder_offline_queuesAndSyncsWhenOnline() {
        val api = InMemoryDolibarrApi()
        val connectivity = InMemoryConnectivityMonitor(false)
        val pushNotifier = InMemoryPushNotifier()
        val realtime = InMemoryRealtimeEventBus()
        val queue = OfflineSyncQueue()
        val repository = OrderRepository(
            api = api,
            workflow = OrderWorkflow(),
            connectivity = connectivity,
            offlineQueue = queue,
            pushNotifier = pushNotifier,
            realtimeEventBus = realtime,
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(setOf("AM-OK")),
            whatsAppNotifier = InMemoryWhatsAppNotifier()
        )

        val created = repository.registerOrder(clientSession, baseOrder("IMP-1002"))
        assertEquals(1, queue.size())

        connectivity.setOnline(true)
        repository.syncOfflineQueue(clientSession)

        assertEquals(0, queue.size())
        assertEquals(1, pushNotifier.notifications.size)
        assertEquals(OrderStatus.PENDING, created.status)
    }

    @Test
    fun fullDeliveryFlow_endsWithCompletedAndWhatsappNotification() {
        val api = InMemoryDolibarrApi()
        val connectivity = InMemoryConnectivityMonitor(true)
        val pushNotifier = InMemoryPushNotifier()
        val realtime = InMemoryRealtimeEventBus()
        val whatsAppNotifier = InMemoryWhatsAppNotifier()
        val repository = OrderRepository(
            api = api,
            workflow = OrderWorkflow(),
            connectivity = connectivity,
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = pushNotifier,
            realtimeEventBus = realtime,
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(setOf("AM-777", "TX-AM-777")),
            whatsAppNotifier = whatsAppNotifier
        )

        val created = repository.registerOrder(clientSession, baseOrder("IMP-1003"))
        val inProgress = repository.courierTakeOrder(session, created.id)
        val waitingQr = repository.requestQrValidation(session, inProgress.id)
        val waitingPayment = repository.verifyQrAndRequestPayment(
            session = session,
            orderId = waitingQr.id,
            scannedToken = waitingQr.qrToken ?: error("qr token missing")
        )
        val completed = repository.completeAfterPayment(session, waitingPayment, "AM-777")

        assertEquals(OrderStatus.COMPLETED, completed.status)
        assertEquals("TX-AM-777", completed.paymentReference)
        assertEquals(250.0, completed.paymentRecord?.amount)
        assertEquals(1, whatsAppNotifier.notifications.size)
        assertTrue(whatsAppNotifier.notifications.first().contains("IMP-1003"))
        assertTrue(realtime.events.any { it.type == RealtimeEventType.DELIVERY_COMPLETED })
    }

    @Test
    fun invalidPaymentReference_throwsValidationError() {
        val repository = OrderRepository(
            api = InMemoryDolibarrApi(),
            workflow = OrderWorkflow(),
            connectivity = InMemoryConnectivityMonitor(true),
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = InMemoryPushNotifier(),
            realtimeEventBus = InMemoryRealtimeEventBus(),
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(emptySet()),
            whatsAppNotifier = InMemoryWhatsAppNotifier()
        )

        val created = repository.registerOrder(clientSession, baseOrder("IMP-1004"))
        val inProgress = repository.courierTakeOrder(session, created.id)
        val waitingQr = repository.requestQrValidation(session, inProgress.id)
        val waitingPayment = repository.verifyQrAndRequestPayment(
            session = session,
            orderId = waitingQr.id,
            scannedToken = waitingQr.qrToken ?: error("qr token missing")
        )

        assertFailsWith<DomainError.PaymentValidationFailed> {
            repository.completeAfterPayment(
                session = session,
                order = waitingPayment,
                paymentReference = "AM-BAD"
            )
        }
    }

    @Test
    fun client_cannot_take_order_forbidden() {
        val repository = OrderRepository(
            api = InMemoryDolibarrApi(),
            workflow = OrderWorkflow(),
            connectivity = InMemoryConnectivityMonitor(true),
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = InMemoryPushNotifier(),
            realtimeEventBus = InMemoryRealtimeEventBus(),
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(setOf("AM-OK")),
            whatsAppNotifier = InMemoryWhatsAppNotifier()
        )
        val created = repository.registerOrder(clientSession, baseOrder("IMP-1005"))
        assertFailsWith<DomainError.Forbidden> {
            repository.courierTakeOrder(clientSession, created.id)
        }
    }

    @Test
    fun courier_can_update_tracking_and_emit_event() {
        val realtime = InMemoryRealtimeEventBus()
        val repository = OrderRepository(
            api = InMemoryDolibarrApi(),
            workflow = OrderWorkflow(),
            connectivity = InMemoryConnectivityMonitor(true),
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = InMemoryPushNotifier(),
            realtimeEventBus = realtime,
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(setOf("AM-OK")),
            whatsAppNotifier = InMemoryWhatsAppNotifier()
        )
        val created = repository.registerOrder(clientSession, baseOrder("IMP-1006"))
        val inProgress = repository.courierTakeOrder(session, created.id)
        val tracked = repository.updateTracking(
            session = session,
            orderId = inProgress.id,
            latitude = -4.321,
            longitude = 15.300,
            etaMinutes = 9
        )
        assertEquals(9, tracked.etaMinutes)
        assertEquals(-4.321, tracked.currentLocation?.latitude)
        assertTrue(realtime.events.any { it.type == RealtimeEventType.TRACKING_UPDATED })
    }
}
