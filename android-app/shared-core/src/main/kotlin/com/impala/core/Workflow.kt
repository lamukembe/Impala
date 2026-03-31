package com.impala.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

sealed class OfflineAction {
    data class CreateOrder(val orderId: String) : OfflineAction()
    data class UpdateStatus(
        val orderId: String,
        val status: OrderStatus,
        val courierId: String? = null,
        val qrToken: String? = null,
        val paymentReference: String? = null
    ) : OfflineAction()
}

enum class OperationResult {
    SUCCESS,
    RETRY
}

class OfflineSyncQueue {
    private val actions = mutableListOf<OfflineAction>()

    fun enqueue(action: OfflineAction) {
        actions += action
    }

    fun drain(): List<OfflineAction> {
        val snapshot = actions.toList()
        actions.clear()
        return snapshot
    }

    fun size(): Int = actions.size
    fun isEmpty(): Boolean = actions.isEmpty()

    fun flush(executor: (OfflineAction) -> OperationResult) {
        val failed = mutableListOf<OfflineAction>()
        actions.forEach { action ->
            val result = executor(action)
            if (result == OperationResult.RETRY) {
                failed += action
            }
        }
        actions.clear()
        actions += failed
    }
}

class OrderWorkflow {
    fun assignCourier(order: DeliveryOrder, courierId: String): DeliveryOrder {
        ensureTransition(order.status, OrderStatus.IN_PROGRESS)
        return order.copy(
            status = OrderStatus.IN_PROGRESS,
            courierId = courierId,
            updatedAt = Instant.now()
        )
    }

    fun startQrValidation(order: DeliveryOrder): DeliveryOrder {
        ensureTransition(order.status, OrderStatus.WAITING_QR_VALIDATION)
        return order.copy(
            status = OrderStatus.WAITING_QR_VALIDATION,
            qrToken = QrService.generateToken(order),
            updatedAt = Instant.now()
        )
    }

    fun validateQr(order: DeliveryOrder, scannedToken: String): DeliveryOrder {
        ensureTransition(order.status, OrderStatus.WAITING_PAYMENT)
        if (order.qrToken.isNullOrBlank() || scannedToken != order.qrToken) {
            throw DomainError.InvalidOrderData("qrToken")
        }
        return order.copy(
            status = OrderStatus.WAITING_PAYMENT,
            updatedAt = Instant.now()
        )
    }

    fun completeAfterPayment(order: DeliveryOrder, paymentReference: String): DeliveryOrder {
        ensureTransition(order.status, OrderStatus.COMPLETED)
        return order.copy(
            status = OrderStatus.COMPLETED,
            paymentReference = paymentReference,
            updatedAt = Instant.now()
        )
    }

    private fun ensureTransition(from: OrderStatus, to: OrderStatus) {
        val allowed = when (from) {
            OrderStatus.PENDING -> setOf(OrderStatus.IN_PROGRESS)
            OrderStatus.IN_PROGRESS -> setOf(OrderStatus.WAITING_QR_VALIDATION)
            OrderStatus.WAITING_QR_VALIDATION -> setOf(OrderStatus.WAITING_PAYMENT)
            OrderStatus.WAITING_PAYMENT -> setOf(OrderStatus.COMPLETED, OrderStatus.FAILED)
            OrderStatus.COMPLETED, OrderStatus.FAILED -> emptySet()
        }
        if (to !in allowed) {
            throw DomainError.InvalidStateTransition(from, to)
        }
    }
}

object QrService {
    fun generateToken(order: DeliveryOrder): String {
        val raw = "${order.id}|${order.orderNumber}|${order.recipientPhoneNumber}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
