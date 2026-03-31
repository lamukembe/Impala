package com.impala.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrderRepositoryTest {
    private val session = UserSession(
        userId = "courier-42",
        role = "courier",
        authToken = "secure-token-1234"
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
        val whatsAppNotifier = InMemoryWhatsAppNotifier()
        val repository = OrderRepository(
            api = api,
            workflow = OrderWorkflow(),
            connectivity = connectivity,
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = pushNotifier,
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(setOf("AM-OK")),
            whatsAppNotifier = whatsAppNotifier
        )

        val created = repository.registerOrder(session, baseOrder())

        assertEquals(OrderStatus.PENDING, created.status)
        assertEquals(1, pushNotifier.notifications.size)
        assertTrue(pushNotifier.notifications.first().contains("NEW_ORDER"))
    }

    @Test
    fun registerOrder_offline_queuesAndSyncsWhenOnline() {
        val api = InMemoryDolibarrApi()
        val connectivity = InMemoryConnectivityMonitor(false)
        val pushNotifier = InMemoryPushNotifier()
        val queue = OfflineSyncQueue()
        val repository = OrderRepository(
            api = api,
            workflow = OrderWorkflow(),
            connectivity = connectivity,
            offlineQueue = queue,
            pushNotifier = pushNotifier,
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(setOf("AM-OK")),
            whatsAppNotifier = InMemoryWhatsAppNotifier()
        )

        val created = repository.registerOrder(session, baseOrder("IMP-1002"))
        assertEquals(1, queue.size())

        connectivity.setOnline(true)
        repository.syncOfflineQueue(session)

        assertEquals(0, queue.size())
        assertEquals(1, pushNotifier.notifications.size)
        assertEquals(OrderStatus.PENDING, created.status)
    }

    @Test
    fun fullDeliveryFlow_endsWithCompletedAndWhatsappNotification() {
        val api = InMemoryDolibarrApi()
        val connectivity = InMemoryConnectivityMonitor(true)
        val pushNotifier = InMemoryPushNotifier()
        val whatsAppNotifier = InMemoryWhatsAppNotifier()
        val repository = OrderRepository(
            api = api,
            workflow = OrderWorkflow(),
            connectivity = connectivity,
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = pushNotifier,
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(setOf("AM-777")),
            whatsAppNotifier = whatsAppNotifier
        )

        val created = repository.registerOrder(session, baseOrder("IMP-1003"))
        val inProgress = repository.courierTakeOrder(session, created.id)
        val waitingQr = repository.requestQrValidation(session, inProgress.id)
        val waitingPayment = repository.verifyQrAndRequestPayment(
            session = session,
            orderId = waitingQr.id,
            scannedToken = waitingQr.qrToken ?: error("qr token missing")
        )
        val completed = repository.completeAfterPayment(session, waitingPayment, "AM-777")

        assertEquals(OrderStatus.COMPLETED, completed.status)
        assertEquals("AM-777", completed.paymentReference)
        assertEquals(1, whatsAppNotifier.notifications.size)
        assertTrue(whatsAppNotifier.notifications.first().contains("IMP-1003"))
    }

    @Test
    fun invalidPaymentReference_throwsValidationError() {
        val repository = OrderRepository(
            api = InMemoryDolibarrApi(),
            workflow = OrderWorkflow(),
            connectivity = InMemoryConnectivityMonitor(true),
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = InMemoryPushNotifier(),
            qrVerifier = InMemoryQrVerifier(),
            paymentGateway = InMemoryPaymentGateway(emptySet()),
            whatsAppNotifier = InMemoryWhatsAppNotifier()
        )

        val created = repository.registerOrder(session, baseOrder("IMP-1004"))
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
}
