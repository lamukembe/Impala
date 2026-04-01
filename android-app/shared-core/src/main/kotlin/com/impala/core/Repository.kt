package com.impala.core

import java.time.Instant
import java.time.format.DateTimeFormatter

interface DolibarrApi {
    fun createOrder(session: UserSession, order: DeliveryOrder): DeliveryOrder
    fun updateOrderStatus(
        session: UserSession,
        orderId: String,
        status: OrderStatus,
        courierId: String? = null,
        paymentReference: String? = null
    ): DeliveryOrder
    fun listPendingOrders(session: UserSession): List<DeliveryOrder>
}

interface ConnectivityMonitor {
    fun isOnline(): Boolean
}

interface PushNotifier {
    fun notifyCouriersNewOrder(order: DeliveryOrder)
}

interface RealtimeEventBus {
    fun publish(event: DeliveryRealtimeEvent)
}

interface WhatsAppNotifier {
    fun notifyClientDeliveryConfirmed(orderNumber: String, recipientPhoneNumber: String)
}

interface PaymentGateway {
    fun validateAirtelMoneyPayment(orderId: String, paymentReference: String, amount: Double): PaymentValidationResult
}

interface QrVerifier {
    fun generateQrToken(orderId: String, recipientPhoneNumber: String): String
    fun verifyQrToken(order: DeliveryOrder, scannedToken: String): Boolean
}

class OrderValidator {
    fun validate(order: DeliveryOrder) {
        if (order.packageType.isBlank()) throw DomainError.InvalidOrderData("packageType")
        if (order.weightKg <= 0) throw DomainError.InvalidOrderData("weightKg")
        if (order.volumeM3 <= 0) throw DomainError.InvalidOrderData("volumeM3")
        if (order.collectionLocation.isBlank()) throw DomainError.InvalidOrderData("collectionLocation")
        if (order.deliveryAddress.isBlank()) throw DomainError.InvalidOrderData("deliveryAddress")
        if (!order.recipientPhoneNumber.matches(Regex("^\\+?[0-9]{8,15}$"))) {
            throw DomainError.InvalidOrderData("recipientPhoneNumber")
        }
        if (order.packageValue <= 0) throw DomainError.InvalidOrderData("packageValue")
    }
}

class OrderRepository(
    private val api: DolibarrApi,
    private val workflow: OrderWorkflow,
    private val connectivity: ConnectivityMonitor,
    private val offlineQueue: OfflineSyncQueue,
    private val pushNotifier: PushNotifier,
    private val realtimeEventBus: RealtimeEventBus,
    private val qrVerifier: QrVerifier,
    private val paymentGateway: PaymentGateway,
    private val whatsAppNotifier: WhatsAppNotifier,
    private val validator: OrderValidator = OrderValidator()
) {
    private val localOrders = linkedMapOf<String, DeliveryOrder>()

    fun registerOrder(session: UserSession, order: DeliveryOrder): DeliveryOrder {
        ensureAuthenticated(session)
        ensureRole(session, UserRole.CLIENT, UserRole.ADMIN)
        validator.validate(order)
        localOrders[order.id] = order

        return if (connectivity.isOnline()) {
            val createdOrder = api.createOrder(session, order)
            localOrders[createdOrder.id] = createdOrder
            pushNotifier.notifyCouriersNewOrder(createdOrder)
            publishOrderEvent(createdOrder, "order_registered")
            createdOrder
        } else {
            offlineQueue.enqueue(OfflineAction.CreateOrder(order.id))
            order
        }
    }

    fun courierTakeOrder(session: UserSession, orderId: String, courierId: String = session.userId): DeliveryOrder {
        ensureAuthenticated(session)
        ensureRole(session, UserRole.COURIER, UserRole.ADMIN)
        val order = getLocalOrder(orderId)
        val next = workflow.assignCourier(order, courierId)
        localOrders[next.id] = next
        return updateStatus(session, next).also { publishOrderEvent(it, "order_taken") }
    }

    fun requestQrValidation(session: UserSession, orderId: String): DeliveryOrder {
        ensureAuthenticated(session)
        ensureRole(session, UserRole.COURIER, UserRole.ADMIN)
        val order = getLocalOrder(orderId)
        val waitingQr = workflow.startQrValidation(order)
        val qrToken = qrVerifier.generateQrToken(waitingQr.id, waitingQr.recipientPhoneNumber)
        val withVerifierToken = waitingQr.copy(qrToken = qrToken)
        localOrders[withVerifierToken.id] = withVerifierToken
        return updateStatus(session, withVerifierToken).also { publishOrderEvent(it, "qr_requested") }
    }

    fun verifyQrAndRequestPayment(
        session: UserSession,
        orderId: String,
        scannedToken: String
    ): DeliveryOrder {
        ensureAuthenticated(session)
        ensureRole(session, UserRole.COURIER, UserRole.ADMIN)
        val order = getLocalOrder(orderId)
        if (!qrVerifier.verifyQrToken(order, scannedToken)) {
            throw DomainError.InvalidOrderData("qrToken")
        }
        val next = workflow.validateQr(order, scannedToken)
        localOrders[next.id] = next
        return updateStatus(session, next).also { publishOrderEvent(it, "qr_verified_waiting_payment") }
    }

    fun completeAfterPayment(
        session: UserSession,
        order: DeliveryOrder,
        paymentReference: String
    ): DeliveryOrder {
        ensureAuthenticated(session)
        ensureRole(session, UserRole.COURIER, UserRole.ADMIN)
        val current = getLocalOrder(order.id)
        val paymentValidation = paymentGateway.validateAirtelMoneyPayment(
            orderId = order.id,
            paymentReference = paymentReference,
            amount = current.packageValue
        )
        if (!paymentValidation.isValid) {
            throw DomainError.PaymentValidationFailed(paymentReference)
        }

        val completed = workflow.completeAfterPayment(current, paymentValidation)
        localOrders[completed.id] = completed
        val saved = updateStatus(session, completed)
        notifyClient(saved)
        publishOrderEvent(saved, "delivery_completed")
        return saved
    }

    fun updateTracking(
        session: UserSession,
        orderId: String,
        latitude: Double,
        longitude: Double,
        speedKmh: Double? = null,
        etaMinutes: Int? = null
    ): DeliveryOrder {
        ensureAuthenticated(session)
        ensureRole(session, UserRole.COURIER, UserRole.ADMIN)
        val order = getLocalOrder(orderId)
        val tracking = TrackingSnapshot(
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            etaMinutes = etaMinutes
        )
        val updated = workflow.updateTracking(order, tracking)
        localOrders[updated.id] = updated
        publishOrderEvent(updated, "tracking_updated")
        return updated
    }

    fun syncOfflineQueue(session: UserSession): List<DeliveryOrder> {
        ensureAuthenticated(session)
        if (!connectivity.isOnline()) throw DomainError.NetworkUnavailable()

        val queued = offlineQueue.drain()
        queued.forEach { action ->
            when (action) {
                is OfflineAction.CreateOrder -> {
                    val order = localOrders[action.orderId] ?: return@forEach
                    val created = api.createOrder(session, order)
                    localOrders[created.id] = created
                    pushNotifier.notifyCouriersNewOrder(created)
                }

                is OfflineAction.UpdateStatus -> {
                    val updated = api.updateOrderStatus(
                        session = session,
                        orderId = action.orderId,
                        status = action.status,
                        courierId = action.courierId,
                        paymentReference = action.paymentReference
                    )
                    localOrders[updated.id] = updated
                    if (updated.status == OrderStatus.COMPLETED) {
                        notifyClient(updated)
                    }
                }
            }
        }
        return localOrders.values.toList()
    }

    fun orderHistory(session: UserSession): List<DeliveryOrder> {
        ensureAuthenticated(session)
        return localOrders.values.sortedByDescending { it.updatedAt }
    }

    fun pendingOrders(session: UserSession): List<DeliveryOrder> {
        ensureAuthenticated(session)
        if (connectivity.isOnline()) {
            api.listPendingOrders(session).forEach { localOrders[it.id] = it }
        }
        return localOrders.values.filter { it.status != OrderStatus.COMPLETED && it.status != OrderStatus.FAILED }
    }

    private fun updateStatus(
        session: UserSession,
        order: DeliveryOrder
    ): DeliveryOrder {
        return if (connectivity.isOnline()) {
            val remoteUpdated = api.updateOrderStatus(
                session = session,
                orderId = order.id,
                status = order.status,
                courierId = order.courierId,
                paymentReference = order.paymentReference
            )
            val merged = remoteUpdated.copy(
                courierId = order.courierId ?: remoteUpdated.courierId,
                qrToken = order.qrToken ?: remoteUpdated.qrToken,
                paymentReference = order.paymentReference ?: remoteUpdated.paymentReference
            )
            localOrders[merged.id] = merged
            merged
        } else {
            offlineQueue.enqueue(
                OfflineAction.UpdateStatus(
                    orderId = order.id,
                    status = order.status,
                    courierId = order.courierId,
                    paymentReference = order.paymentReference
                )
            )
            order
        }
    }

    private fun ensureAuthenticated(session: UserSession) {
        if (session.authToken.isBlank()) throw DomainError.Unauthorized()
    }

    private fun ensureRole(session: UserSession, vararg allowedRoles: UserRole) {
        if (session.role !in allowedRoles) {
            throw DomainError.Forbidden(allowedRoles.first(), session.role)
        }
    }

    private fun getLocalOrder(orderId: String): DeliveryOrder {
        return localOrders[orderId] ?: throw DomainError.OrderNotFound(orderId)
    }

    private fun notifyClient(order: DeliveryOrder) {
        whatsAppNotifier.notifyClientDeliveryConfirmed(
            orderNumber = order.orderNumber,
            recipientPhoneNumber = order.recipientPhoneNumber
        )
    }

    private fun publishOrderEvent(order: DeliveryOrder, action: String) {
        realtimeEventBus.publish(
            DeliveryRealtimeEvent(
                orderId = order.id,
                orderNumber = order.orderNumber,
                type = when (action) {
                    "order_registered" -> RealtimeEventType.ORDER_CREATED
                    "order_taken" -> RealtimeEventType.ORDER_ASSIGNED
                    "qr_requested" -> RealtimeEventType.QR_VALIDATION_STARTED
                    "qr_verified_waiting_payment" -> RealtimeEventType.QR_VALIDATED
                    "delivery_completed" -> RealtimeEventType.DELIVERY_COMPLETED
                    "tracking_updated" -> RealtimeEventType.TRACKING_UPDATED
                    else -> RealtimeEventType.ORDER_CREATED
                },
                actorUserId = order.courierId ?: "system",
                payload = mapOf(
                    "status" to order.status.name,
                    "action" to action
                ),
                emittedAt = order.updatedAt
            )
        )
    }
}

class InMemoryConnectivityMonitor(private var online: Boolean = true) : ConnectivityMonitor {
    override fun isOnline(): Boolean = online
    fun setOnline(value: Boolean) {
        online = value
    }
}

class InMemoryDolibarrApi : DolibarrApi {
    private val remoteOrders = linkedMapOf<String, DeliveryOrder>()

    override fun createOrder(session: UserSession, order: DeliveryOrder): DeliveryOrder {
        val now = Instant.now()
        val created = order.copy(
            createdAt = order.createdAt.takeIf { it.toEpochMilli() > 0 } ?: now,
            updatedAt = now
        )
        remoteOrders[created.id] = created
        return created
    }

    override fun updateOrderStatus(
        session: UserSession,
        orderId: String,
        status: OrderStatus,
        courierId: String?,
        paymentReference: String?
    ): DeliveryOrder {
        val existing = remoteOrders[orderId] ?: throw DomainError.OrderNotFound(orderId)
        val updated = existing.copy(
            status = status,
            courierId = courierId ?: existing.courierId,
            paymentReference = paymentReference ?: existing.paymentReference,
            updatedAt = Instant.now()
        )
        remoteOrders[orderId] = updated
        return updated
    }

    override fun listPendingOrders(session: UserSession): List<DeliveryOrder> = remoteOrders.values.toList()
}

class InMemoryPushNotifier : PushNotifier {
    val notifications = mutableListOf<String>()

    override fun notifyCouriersNewOrder(order: DeliveryOrder) {
        notifications += "NEW_ORDER:${order.orderNumber}"
    }
}

class InMemoryRealtimeEventBus : RealtimeEventBus {
    val events = mutableListOf<DeliveryRealtimeEvent>()

    override fun publish(event: DeliveryRealtimeEvent) {
        events += event
    }
}

class InMemoryWhatsAppNotifier : WhatsAppNotifier {
    val notifications = mutableListOf<String>()

    override fun notifyClientDeliveryConfirmed(orderNumber: String, recipientPhoneNumber: String) {
        notifications += "$recipientPhoneNumber|$orderNumber"
    }
}

class InMemoryQrVerifier : QrVerifier {
    override fun generateQrToken(orderId: String, recipientPhoneNumber: String): String {
        val raw = "$orderId|$recipientPhoneNumber"
        return SecureTokenStore.hash(raw).take(32)
    }

    override fun verifyQrToken(order: DeliveryOrder, scannedToken: String): Boolean {
        return !order.qrToken.isNullOrBlank() && order.qrToken == scannedToken
    }
}

class InMemoryPaymentGateway(private val validReferences: Set<String> = emptySet()) : PaymentGateway {
    override fun validateAirtelMoneyPayment(
        orderId: String,
        paymentReference: String,
        amount: Double
    ): PaymentValidationResult {
        val valid = validReferences.contains("$orderId|$paymentReference") || validReferences.contains(paymentReference)
        return PaymentValidationResult(
            isValid = valid,
            providerTransactionId = if (valid) "TX-$paymentReference" else null,
            validatedAmount = if (valid) amount else null
        )
    }
}

fun generateOrderNumber(now: Instant = Instant.now()): String {
    val ts = DateTimeFormatter.ISO_INSTANT.format(now).replace(":", "").replace("-", "")
    return "IMP-$ts"
}
