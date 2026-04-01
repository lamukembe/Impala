package com.impala.core

import java.time.Instant
import java.util.UUID

enum class DeliveryType {
    URGENT,
    IMMEDIATE,
    NORMAL,
    DEFERRED
}

enum class UserRole {
    CLIENT,
    COURIER,
    ADMIN;

    companion object {
        fun fromValue(value: String): UserRole? {
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}

enum class OrderStatus {
    PENDING,
    IN_PROGRESS,
    WAITING_QR_VALIDATION,
    WAITING_PAYMENT,
    COMPLETED,
    FAILED
}

enum class RealtimeEventType {
    ORDER_CREATED,
    ORDER_ASSIGNED,
    QR_VALIDATION_STARTED,
    QR_VALIDATED,
    TRACKING_UPDATED,
    PAYMENT_VALIDATED,
    DELIVERY_COMPLETED,
    OFFLINE_ACTION_QUEUED,
    OFFLINE_ACTION_SYNCED
}

enum class TrackingEventType {
    CREATED,
    COURIER_ASSIGNED,
    PICKED_UP,
    IN_TRANSIT,
    ARRIVED_AT_DESTINATION,
    DELIVERED
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null
)

data class TrackingEvent(
    val eventType: TrackingEventType,
    val at: Instant = Instant.now(),
    val note: String? = null,
    val position: GeoPoint? = null
)

data class PaymentRecord(
    val reference: String,
    val provider: String = "AIRTEL_MONEY",
    val amount: Double,
    val currency: String = "CDF",
    val validatedAt: Instant = Instant.now(),
    val validatedBy: String
)

data class DeliveryRealtimeEvent(
    val orderId: String,
    val orderNumber: String,
    val type: RealtimeEventType,
    val actorUserId: String,
    val payload: Map<String, String> = emptyMap(),
    val emittedAt: Instant = Instant.now()
)

sealed class RealtimeEvent {
    data class OrderUpdated(
        val orderId: String,
        val orderNumber: String,
        val status: OrderStatus,
        val action: String,
        val occurredAt: Instant
    ) : RealtimeEvent()
}

data class TrackingLocation(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double? = null,
    val etaMinutes: Int? = null,
    val capturedAt: Instant = Instant.now()
)

data class PaymentValidationResult(
    val isValid: Boolean,
    val providerTransactionId: String? = null,
    val validatedAmount: Double? = null
)

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
    val paymentRecord: PaymentRecord? = null,
    val etaMinutes: Int? = null,
    val currentLocation: GeoPoint? = null,
    val tracking: List<TrackingEvent> = emptyList(),
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
    class Forbidden(actual: UserRole, allowed: Set<UserRole>) :
        DomainError("Forbidden. Actual role: $actual, allowed roles: $allowed")
    class InvalidRole(role: String) : DomainError("Invalid role: $role")
    class OrderNotFound(orderId: String) : DomainError("Order not found: $orderId")
    class PaymentValidationFailed(reference: String) :
        DomainError("Airtel Money payment validation failed for reference: $reference")
    class InvalidStateTransition(from: OrderStatus, to: OrderStatus) :
        DomainError("Invalid transition from $from to $to")
}
