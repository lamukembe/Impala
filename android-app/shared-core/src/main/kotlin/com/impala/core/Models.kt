package com.impala.core

import java.time.Instant
import java.util.UUID

enum class DeliveryType {
    URGENT,
    IMMEDIATE,
    NORMAL,
    DEFERRED
}

enum class OrderStatus {
    PENDING,
    IN_PROGRESS,
    WAITING_QR_VALIDATION,
    WAITING_PAYMENT,
    COMPLETED,
    FAILED
}

data class DeliveryOrder(
    val id: String = UUID.randomUUID().toString(),
    val orderNumber: String,
    val packageType: String,
    val weightKg: Double,
    val volumeM3: Double,
    val collectionLocation: String,
    val deliveryAddress: String,
    val recipientPhoneNumber: String,
    val deliveryType: DeliveryType,
    val packageValue: Double,
    val status: OrderStatus = OrderStatus.PENDING,
    val courierId: String? = null,
    val qrToken: String? = null,
    val paymentReference: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

data class UserSession(
    val userId: String,
    val role: String,
    val authToken: String
)

sealed class DomainError(message: String) : RuntimeException(message) {
    class NetworkUnavailable : DomainError("Network is unavailable")
    class InvalidOrderData(field: String) : DomainError("Invalid order field: $field")
    class Unauthorized : DomainError("Unauthorized access")
    class OrderNotFound(orderId: String) : DomainError("Order not found: $orderId")
    class PaymentValidationFailed(reference: String) :
        DomainError("Airtel Money payment validation failed for reference: $reference")
    class InvalidStateTransition(from: OrderStatus, to: OrderStatus) :
        DomainError("Invalid transition from $from to $to")
}
