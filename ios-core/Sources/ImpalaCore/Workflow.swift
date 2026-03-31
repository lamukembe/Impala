import Foundation
import CryptoKit

public enum OfflineAction: Equatable {
    case createOrder(orderId: String)
    case updateStatus(orderId: String, status: OrderStatus, courierId: String?, paymentReference: String?)
}

public final class OfflineQueue {
    private var actions: [OfflineAction] = []

    public init() {}

    public func enqueue(_ action: OfflineAction) {
        actions.append(action)
    }

    public func drain() -> [OfflineAction] {
        let snapshot = actions
        actions.removeAll()
        return snapshot
    }

    public func size() -> Int {
        actions.count
    }
}

public enum OperationResult {
    case success
    case retry
}

public final class OrderWorkflow {
    public init() {}

    public func assignCourier(order: DeliveryOrder, courierId: String) throws -> DeliveryOrder {
        try ensureTransition(from: order.status, to: .inProgress)
        var next = order
        next.status = .inProgress
        next.courierId = courierId
        next.updatedAt = Date()
        return next
    }

    public func startQrValidation(order: DeliveryOrder) throws -> DeliveryOrder {
        try ensureTransition(from: order.status, to: .waitingQrValidation)
        var next = order
        next.status = .waitingQrValidation
        next.qrToken = QrService.generateToken(order: order)
        next.updatedAt = Date()
        return next
    }

    public func validateQr(order: DeliveryOrder, scannedToken: String) throws -> DeliveryOrder {
        try ensureTransition(from: order.status, to: .waitingPayment)
        guard let token = order.qrToken, !token.isEmpty, token == scannedToken else {
            throw DomainError.invalidOrderData("qrToken")
        }
        var next = order
        next.status = .waitingPayment
        next.updatedAt = Date()
        return next
    }

    public func completeAfterPayment(order: DeliveryOrder, paymentReference: String) throws -> DeliveryOrder {
        try ensureTransition(from: order.status, to: .completed)
        var next = order
        next.status = .completed
        next.paymentReference = paymentReference
        next.updatedAt = Date()
        return next
    }

    private func ensureTransition(from: OrderStatus, to: OrderStatus) throws {
        let allowed: [OrderStatus]
        switch from {
        case .pending:
            allowed = [.inProgress]
        case .inProgress:
            allowed = [.waitingQrValidation]
        case .waitingQrValidation:
            allowed = [.waitingPayment]
        case .waitingPayment:
            allowed = [.completed, .failed]
        case .completed, .failed:
            allowed = []
        }

        if !allowed.contains(to) {
            throw DomainError.invalidStateTransition(from: from, to: to)
        }
    }
}

public enum QrService {
    public static func generateToken(order: DeliveryOrder) -> String {
        let raw = "\(order.id.uuidString)|\(order.orderNumber)|\(order.recipientPhoneNumber)"
        let digest = SHA256.hash(data: Data(raw.utf8))
        return digest.compactMap { String(format: "%02x", $0) }.joined().prefix(32).description
    }
}
