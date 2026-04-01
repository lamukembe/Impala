import Foundation

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
            throw ImpalaError.invalidOrderData("qrToken")
        }
        var next = order
        next.status = .waitingPayment
        next.updatedAt = Date()
        return next
    }

    public func completeAfterPayment(order: DeliveryOrder, payment: PaymentRecord) throws -> DeliveryOrder {
        try ensureTransition(from: order.status, to: .completed)
        var next = order
        next.status = .completed
        next.paymentReference = payment.reference
        next.paymentRecord = payment
        next.tracking.append(
            TrackingEvent(
                eventType: .delivered,
                note: "Livraison validee apres paiement \(payment.reference)",
                position: next.currentLocation
            )
        )
        next.updatedAt = Date()
        return next
    }

    public func updateTracking(order: DeliveryOrder, snapshot: TrackingSnapshot) throws -> DeliveryOrder {
        if order.status == .completed || order.status == .failed {
            throw ImpalaError.invalidStateTransition(from: order.status, to: order.status)
        }
        var next = order
        let point = GeoPoint(latitude: snapshot.latitude, longitude: snapshot.longitude, accuracyMeters: snapshot.accuracyMeters)
        next.currentLocation = point
        next.etaMinutes = snapshot.etaMinutes
        let eventType: TrackingEventType = {
            switch order.status {
            case .pending: return .created
            case .inProgress: return .inTransit
            case .waitingQrValidation, .waitingPayment: return .arrivedAtDestination
            case .completed: return .delivered
            case .failed: return .inTransit
            }
        }()
        next.tracking.append(
            TrackingEvent(eventType: eventType, note: "Tracking update", position: point)
        )
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
            throw ImpalaError.invalidStateTransition(from: from, to: to)
        }
    }
}

public enum QrService {
    public static func generateToken(order: DeliveryOrder) -> String {
        let raw = "\(order.id.uuidString)|\(order.orderNumber)|\(order.recipientPhoneNumber)"
        return simpleHash(raw).prefix(32).description
    }

    private static func simpleHash(_ text: String) -> String {
        let bytes = [UInt8](text.utf8)
        var hash = [UInt8](repeating: 0, count: 32)
        for (index, byte) in bytes.enumerated() {
            let slot = index % 32
            hash[slot] = hash[slot] &+ byte &+ UInt8(slot)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}
