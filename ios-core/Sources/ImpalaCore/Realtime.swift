import Foundation

public enum RealtimeEventType: String, Codable, CaseIterable {
    case orderCreated
    case orderAssigned
    case qrValidationStarted
    case qrValidated
    case trackingUpdated
    case paymentValidated
    case deliveryCompleted
    case offlineActionQueued
    case offlineActionSynced
}

public enum TrackingEventType: String, Codable, CaseIterable {
    case created
    case courierAssigned
    case pickedUp
    case inTransit
    case arrivedAtDestination
    case delivered
}

public struct GeoPoint: Codable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let accuracyMeters: Double?

    public init(latitude: Double, longitude: Double, accuracyMeters: Double? = nil) {
        self.latitude = latitude
        self.longitude = longitude
        self.accuracyMeters = accuracyMeters
    }
}

public struct TrackingEvent: Codable, Equatable {
    public let eventType: TrackingEventType
    public let at: Date
    public let note: String?
    public let position: GeoPoint?

    public init(
        eventType: TrackingEventType,
        at: Date = Date(),
        note: String? = nil,
        position: GeoPoint? = nil
    ) {
        self.eventType = eventType
        self.at = at
        self.note = note
        self.position = position
    }
}

public struct PaymentRecord: Codable, Equatable {
    public let reference: String
    public let provider: String
    public let amount: Double
    public let currency: String
    public let validatedAt: Date
    public let validatedBy: String

    public init(
        reference: String,
        provider: String = "AIRTEL_MONEY",
        amount: Double,
        currency: String = "CDF",
        validatedAt: Date = Date(),
        validatedBy: String
    ) {
        self.reference = reference
        self.provider = provider
        self.amount = amount
        self.currency = currency
        self.validatedAt = validatedAt
        self.validatedBy = validatedBy
    }
}

public struct DeliveryRealtimeEvent: Codable, Equatable {
    public let orderId: UUID
    public let orderNumber: String
    public let type: RealtimeEventType
    public let actorUserId: String
    public let payload: [String: String]
    public let emittedAt: Date

    public init(
        orderId: UUID,
        orderNumber: String,
        type: RealtimeEventType,
        actorUserId: String,
        payload: [String: String] = [:],
        emittedAt: Date = Date()
    ) {
        self.orderId = orderId
        self.orderNumber = orderNumber
        self.type = type
        self.actorUserId = actorUserId
        self.payload = payload
        self.emittedAt = emittedAt
    }
}

public struct TrackingSnapshot: Equatable {
    public let latitude: Double
    public let longitude: Double
    public let speedKmh: Double?
    public let etaMinutes: Int?

    public init(latitude: Double, longitude: Double, speedKmh: Double? = nil, etaMinutes: Int? = nil) {
        self.latitude = latitude
        self.longitude = longitude
        self.speedKmh = speedKmh
        self.etaMinutes = etaMinutes
    }
}

public struct PaymentValidationResult: Equatable {
    public let isValid: Bool
    public let providerTransactionId: String?
    public let validatedAmount: Double?

    public init(isValid: Bool, providerTransactionId: String? = nil, validatedAmount: Double? = nil) {
        self.isValid = isValid
        self.providerTransactionId = providerTransactionId
        self.validatedAmount = validatedAmount
    }
}

