package com.impala.app.data

import com.impala.core.ConnectivityMonitor
import com.impala.core.DeliveryOrder
import com.impala.core.DolibarrApi
import com.impala.core.OrderStatus
import com.impala.core.QrVerifier
import com.impala.core.SecureTokenStore
import com.impala.core.UserSession
import com.impala.core.WhatsAppNotifier
import com.impala.core.PaymentGateway
import com.impala.core.PushNotifier
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class DemoConnectivityMonitor(private var online: Boolean = true) : ConnectivityMonitor {
    override fun isOnline(): Boolean = online
    fun setOnline(value: Boolean) {
        online = value
    }
}

class DemoDolibarrApi : DolibarrApi {
    private val db = ConcurrentHashMap<String, DeliveryOrder>()

    override fun createOrder(session: UserSession, order: DeliveryOrder): DeliveryOrder {
        val now = Instant.now()
        val created = order.copy(createdAt = now, updatedAt = now)
        db[created.id] = created
        return created
    }

    override fun updateOrderStatus(
        session: UserSession,
        orderId: String,
        status: OrderStatus,
        courierId: String?,
        paymentReference: String?
    ): DeliveryOrder {
        val existing = db[orderId] ?: throw IllegalArgumentException("Unknown order: $orderId")
        val updated = existing.copy(
            status = status,
            courierId = courierId ?: existing.courierId,
            paymentReference = paymentReference ?: existing.paymentReference,
            updatedAt = Instant.now()
        )
        db[orderId] = updated
        return updated
    }

    override fun listPendingOrders(session: UserSession): List<DeliveryOrder> {
        return db.values.sortedByDescending { it.updatedAt }
    }
}

class DemoPushNotifier : PushNotifier {
    private val buffer = mutableListOf<String>()
    override fun notifyCouriersNewOrder(order: DeliveryOrder) {
        buffer += "New order ${order.orderNumber}"
    }
}

class DemoWhatsAppNotifier : WhatsAppNotifier {
    private val buffer = mutableListOf<String>()
    override fun notifyClientDeliveryConfirmed(orderNumber: String, recipientPhoneNumber: String) {
        buffer += "$recipientPhoneNumber: Votre commande IMPALA #$orderNumber est confirmee."
    }
}

class DemoQrVerifier : QrVerifier {
    override fun generateQrToken(orderId: String, recipientPhoneNumber: String): String {
        return SecureTokenStore.hash("$orderId|$recipientPhoneNumber").take(32)
    }

    override fun verifyQrToken(order: DeliveryOrder, scannedToken: String): Boolean {
        return !order.qrToken.isNullOrBlank() && scannedToken == order.qrToken
    }
}

class DemoPaymentGateway : PaymentGateway {
    override fun validateAirtelMoneyPayment(orderId: String, paymentReference: String): Boolean {
        return paymentReference.startsWith("AM-")
    }
}
