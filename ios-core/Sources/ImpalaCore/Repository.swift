import Foundation

public protocol DolibarrAPI {
    func createOrder(session: UserSession, order: DeliveryOrder) throws -> DeliveryOrder
    func updateOrderStatus(
        session: UserSession,
        orderId: UUID,
        status: OrderStatus,
        courierId: String?,
        paymentReference: String?
    ) throws -> DeliveryOrder
    func listOrders(session: UserSession) throws -> [DeliveryOrder]
}

public protocol ConnectivityChecking {
    var isOnline: Bool { get set }
}

public protocol PushNotifying {
    func notifyCouriersNewOrder(_ order: DeliveryOrder)
}

public protocol WhatsAppNotifying {
    func notifyClient(orderNumber: String, recipientPhoneNumber: String)
}

public protocol AirtelPaymentValidating {
    func validate(orderId: UUID, paymentReference: String) -> Bool
}

public protocol QrVerifying {
    func generate(orderId: UUID, recipientPhoneNumber: String) -> String
    func verify(order: DeliveryOrder, scannedToken: String) -> Bool
}

public enum OfflineAction: Equatable {
    case createOrder(orderId: UUID)
    case updateStatus(orderId: UUID, status: OrderStatus, courierId: String?, paymentReference: String?)
}

public final class OfflineQueue {
    private(set) var actions: [OfflineAction] = []

    public init() {}

    public func enqueue(_ action: OfflineAction) {
        actions.append(action)
    }

    public func drain() -> [OfflineAction] {
        let snapshot = actions
        actions.removeAll()
        return snapshot
    }

    public var count: Int { actions.count }
}

public final class OrderRepository {
    private let api: DolibarrAPI
    private let workflow: OrderWorkflow
    private let pushNotifier: PushNotifying
    private let whatsAppNotifier: WhatsAppNotifying
    private let paymentValidator: AirtelPaymentValidating
    private let qrVerifier: QrVerifying
    private let connectivity: ConnectivityChecking
    private let offlineQueue: OfflineQueue
    private var localOrders: [UUID: DeliveryOrder] = [:]

    public init(
        api: DolibarrAPI,
        workflow: OrderWorkflow,
        pushNotifier: PushNotifying,
        whatsAppNotifier: WhatsAppNotifying,
        paymentValidator: AirtelPaymentValidating,
        qrVerifier: QrVerifying,
        connectivity: ConnectivityChecking,
        offlineQueue: OfflineQueue
    ) {
        self.api = api
        self.workflow = workflow
        self.pushNotifier = pushNotifier
        self.whatsAppNotifier = whatsAppNotifier
        self.paymentValidator = paymentValidator
        self.qrVerifier = qrVerifier
        self.connectivity = connectivity
        self.offlineQueue = offlineQueue
    }

    public func registerOrder(session: UserSession, order: DeliveryOrder) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        try validate(order)
        localOrders[order.id] = order

        if connectivity.isOnline {
            let created = try api.createOrder(session: session, order: order)
            localOrders[created.id] = created
            pushNotifier.notifyCouriersNewOrder(created)
            return created
        } else {
            offlineQueue.enqueue(.createOrder(orderId: order.id))
            return order
        }
    }

    public func courierTakeOrder(session: UserSession, orderId: UUID, courierId: String) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        guard let order = localOrders[orderId] else { throw ImpalaError.orderNotFound(orderId) }
        let updated = try workflow.assignCourier(order: order, courierId: courierId)
        localOrders[updated.id] = updated
        return try persistStatus(session: session, order: updated)
    }

    public func requestQrValidation(session: UserSession, orderId: UUID) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        guard let order = localOrders[orderId] else { throw ImpalaError.orderNotFound(orderId) }
        var waitingQr = try workflow.startQrValidation(order: order)
        waitingQr.qrToken = qrVerifier.generate(orderId: waitingQr.id, recipientPhoneNumber: waitingQr.recipientPhoneNumber)
        localOrders[waitingQr.id] = waitingQr
        return try persistStatus(session: session, order: waitingQr)
    }

    public func verifyQrAndRequestPayment(
        session: UserSession,
        orderId: UUID,
        scannedToken: String
    ) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        guard let order = localOrders[orderId] else { throw ImpalaError.orderNotFound(orderId) }
        guard qrVerifier.verify(order: order, scannedToken: scannedToken) else {
            throw ImpalaError.invalidOrderData("qrToken")
        }
        let waitingPayment = try workflow.validateQr(order: order, scannedToken: scannedToken)
        localOrders[waitingPayment.id] = waitingPayment
        return try persistStatus(session: session, order: waitingPayment)
    }

    public func completeAfterPayment(
        session: UserSession,
        orderId: UUID,
        paymentReference: String
    ) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        guard let order = localOrders[orderId] else { throw ImpalaError.orderNotFound(orderId) }
        guard paymentValidator.validate(orderId: order.id, paymentReference: paymentReference) else {
            throw ImpalaError.paymentValidationFailed(paymentReference)
        }
        let completed = try workflow.completeAfterPayment(order: order, paymentReference: paymentReference)
        localOrders[completed.id] = completed
        let persisted = try persistStatus(session: session, order: completed)
        whatsAppNotifier.notifyClient(orderNumber: persisted.orderNumber, recipientPhoneNumber: persisted.recipientPhoneNumber)
        return persisted
    }

    public func syncOfflineQueue(session: UserSession) throws -> [DeliveryOrder] {
        try ensureAuthenticated(session)
        guard connectivity.isOnline else { throw ImpalaError.networkUnavailable }

        let actions = offlineQueue.drain()
        for action in actions {
            switch action {
            case .createOrder(let orderId):
                guard let order = localOrders[orderId] else { continue }
                let created = try api.createOrder(session: session, order: order)
                localOrders[created.id] = created
                pushNotifier.notifyCouriersNewOrder(created)
            case .updateStatus(let orderId, let status, let courierId, let paymentReference):
                let updated = try api.updateOrderStatus(
                    session: session,
                    orderId: orderId,
                    status: status,
                    courierId: courierId,
                    paymentReference: paymentReference
                )
                localOrders[updated.id] = updated
                if updated.status == .completed {
                    whatsAppNotifier.notifyClient(
                        orderNumber: updated.orderNumber,
                        recipientPhoneNumber: updated.recipientPhoneNumber
                    )
                }
            }
        }
        return orderHistory(session: session)
    }

    public func orderHistory(session: UserSession) -> [DeliveryOrder] {
        localOrders.values.sorted { $0.updatedAt > $1.updatedAt }
    }

    public func pendingOrders(session: UserSession) throws -> [DeliveryOrder] {
        try ensureAuthenticated(session)
        if connectivity.isOnline {
            let remote = try api.listOrders(session: session)
            for order in remote {
                localOrders[order.id] = order
            }
        }
        return localOrders.values.filter { $0.status != .completed && $0.status != .failed }
    }

    private func persistStatus(session: UserSession, order: DeliveryOrder) throws -> DeliveryOrder {
        if connectivity.isOnline {
            let updated = try api.updateOrderStatus(
                session: session,
                orderId: order.id,
                status: order.status,
                courierId: order.courierId,
                paymentReference: order.paymentReference
            )
            localOrders[updated.id] = updated
            return updated
        } else {
            offlineQueue.enqueue(.updateStatus(
                orderId: order.id,
                status: order.status,
                courierId: order.courierId,
                paymentReference: order.paymentReference
            ))
            return order
        }
    }

    private func ensureAuthenticated(_ session: UserSession) throws {
        if session.authToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ImpalaError.unauthorized
        }
    }

    private func validate(_ order: DeliveryOrder) throws {
        if order.packageType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ImpalaError.invalidOrderData("packageType")
        }
        if order.weightKg <= 0 {
            throw ImpalaError.invalidOrderData("weightKg")
        }
        if order.volumeM3 <= 0 {
            throw ImpalaError.invalidOrderData("volumeM3")
        }
        if order.collectionLocation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ImpalaError.invalidOrderData("collectionLocation")
        }
        if order.deliveryAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ImpalaError.invalidOrderData("deliveryAddress")
        }
        if order.packageValue <= 0 {
            throw ImpalaError.invalidOrderData("packageValue")
        }
        if order.recipientPhoneNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ImpalaError.invalidOrderData("recipientPhoneNumber")
        }
    }
}

public final class InMemoryConnectivity: ConnectivityChecking {
    public var isOnline: Bool
    public init(isOnline: Bool = true) {
        self.isOnline = isOnline
    }
}

public final class InMemoryDolibarrAPI: DolibarrAPI {
    private var orders: [UUID: DeliveryOrder] = [:]

    public init() {}

    public func createOrder(session: UserSession, order: DeliveryOrder) throws -> DeliveryOrder {
        var copy = order
        copy.updatedAt = Date()
        orders[copy.id] = copy
        return copy
    }

    public func updateOrderStatus(
        session: UserSession,
        orderId: UUID,
        status: OrderStatus,
        courierId: String?,
        paymentReference: String?
    ) throws -> DeliveryOrder {
        guard var order = orders[orderId] else { throw ImpalaError.orderNotFound(orderId) }
        order.status = status
        if let courierId {
            order.courierId = courierId
        }
        if let paymentReference {
            order.paymentReference = paymentReference
        }
        order.updatedAt = Date()
        orders[orderId] = order
        return order
    }

    public func listOrders(session: UserSession) throws -> [DeliveryOrder] {
        Array(orders.values)
    }
}

public final class InMemoryPushNotifier: PushNotifying {
    public private(set) var notifications: [String] = []
    public init() {}
    public func notifyCouriersNewOrder(_ order: DeliveryOrder) {
        notifications.append("NEW_ORDER:\(order.orderNumber)")
    }
}

public final class InMemoryWhatsAppNotifier: WhatsAppNotifying {
    public private(set) var notifications: [String] = []
    public init() {}
    public func notifyClient(orderNumber: String, recipientPhoneNumber: String) {
        notifications.append("\(recipientPhoneNumber)|\(orderNumber)")
    }
}

public final class InMemoryPaymentValidator: AirtelPaymentValidating {
    private let validReferences: Set<String>
    public init(validReferences: Set<String>) {
        self.validReferences = validReferences
    }
    public func validate(orderId: UUID, paymentReference: String) -> Bool {
        validReferences.contains(paymentReference) || validReferences.contains("\(orderId.uuidString)|\(paymentReference)")
    }
}

public final class DefaultQrVerifier: QrVerifying {
    public init() {}
    public func generate(orderId: UUID, recipientPhoneNumber: String) -> String {
        let raw = "\(orderId.uuidString)|\(recipientPhoneNumber)"
        return Self.sha256Hex(raw).prefix(32).description
    }

    public func verify(order: DeliveryOrder, scannedToken: String) -> Bool {
        guard let token = order.qrToken else { return false }
        return token == scannedToken
    }

    private static func sha256Hex(_ text: String) -> String {
        let bytes = [UInt8](text.utf8)
        var hash = [UInt8](repeating: 0, count: 32)
        for (index, byte) in bytes.enumerated() {
            let slot = index % 32
            hash[slot] = hash[slot] &+ byte &+ UInt8(slot)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}
