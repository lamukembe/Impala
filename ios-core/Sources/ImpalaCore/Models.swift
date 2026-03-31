import Foundation

public enum DeliveryType: String, Codable, CaseIterable {
    case urgent
    case immediate
    case normal
    case deferred
}

public enum OrderStatus: String, Codable, CaseIterable {
    case pending
    case inProgress = "in_progress"
    case waitingQrValidation = "waiting_qr_validation"
    case waitingPayment = "waiting_payment"
    case completed
    case failed
}

public struct DeliveryOrder: Codable, Equatable, Identifiable {
    public let id: UUID
    public let orderNumber: String
    public let packageType: String
    public let weightKg: Double
    public let volumeM3: Double
    public let collectionLocation: String
    public let deliveryAddress: String
    public let recipientPhoneNumber: String
    public let deliveryType: DeliveryType
    public let packageValue: Double
    public var status: OrderStatus
    public var courierId: String?
    public var qrToken: String?
    public var paymentReference: String?
    public let createdAt: Date
    public var updatedAt: Date

    public init(
        id: UUID = UUID(),
        orderNumber: String,
        packageType: String,
        weightKg: Double,
        volumeM3: Double,
        collectionLocation: String,
        deliveryAddress: String,
        recipientPhoneNumber: String,
        deliveryType: DeliveryType,
        packageValue: Double,
        status: OrderStatus = .pending,
        courierId: String? = nil,
        qrToken: String? = nil,
        paymentReference: String? = nil,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.orderNumber = orderNumber
        self.packageType = packageType
        self.weightKg = weightKg
        self.volumeM3 = volumeM3
        self.collectionLocation = collectionLocation
        self.deliveryAddress = deliveryAddress
        self.recipientPhoneNumber = recipientPhoneNumber
        self.deliveryType = deliveryType
        self.packageValue = packageValue
        self.status = status
        self.courierId = courierId
        self.qrToken = qrToken
        self.paymentReference = paymentReference
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

public struct UserSession: Equatable {
    public let userId: String
    public let role: String
    public let authToken: String

    public init(userId: String, role: String, authToken: String) {
        self.userId = userId
        self.role = role
        self.authToken = authToken
    }
}

public enum DomainError: Error, Equatable {
    case networkUnavailable
    case invalidOrderData(field: String)
    case unauthorized
    case orderNotFound
    case invalidStateTransition
    case paymentValidationFailed
}
