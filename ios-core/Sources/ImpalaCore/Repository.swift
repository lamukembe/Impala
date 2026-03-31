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
    var isOnlineValue: Bool { get set }
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

public final class OrderRepository {
    private let api: DolibarrAPI
    private let workflow: OrderWorkflow
    private let connectivity: ConnectivityChecking
    private let offlineQueue: OfflineSyncQueue
    private let pushNotifier: PushNotifying
    private let qrVerifier: QrVerifying
    private let paymentGateway: AirtelPaymentValidating
    private let whatsAppNotifier: WhatsAppNotifying
    private var localOrders: [UUID: DeliveryOrder] = [:]

    public init(
        api: DolibarrAPI,
        workflow: OrderWorkflow,
        connectivity: ConnectivityChecking,
        offlineQueue: OfflineSyncQueue,
        pushNotifier: PushNotifying,
        qrVerifier: QrVerifying,
        paymentGateway: AirtelPaymentValidating,
        whatsAppNotifier: WhatsAppNotifying
    ) {
        self.api = api
        self.workflow = workflow
        self.connectivity = connectivity
        self.offlineQueue = offlineQueue
        self.pushNotifier = pushNotifier
        self.qrVerifier = qrVerifier
        self.paymentGateway = paymentGateway
        self.whatsAppNotifier = whatsAppNotifier
    }

    public func registerOrder(session: UserSession, order: DeliveryOrder) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        try validate(order)
        localOrders[order.id] = order

        if connectivity.isOnlineValue {
            let created = try api.createOrder(session: session, order: order)
            localOrders[created.id] = created
            pushNotifier.notifyCouriersNewOrder(created)
            return created
        }

        offlineQueue.enqueue(.createOrder(orderId: order.id))
        return order
    }

    public func courierTakeOrder(session: UserSession, orderId: UUID, courierId: String) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        guard let order = localOrders[orderId] else { throw DomainError.orderNotFound(orderId) }
        let next = try workflow.assignCourier(order: order, courierId: courierId)
        localOrders[next.id] = next
        return try persistStatus(session: session, order: next)
    }

    public func requestQrValidation(session: UserSession, orderId: UUID) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        guard let order = localOrders[orderId] else { throw DomainError.orderNotFound(orderId) }
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
        guard let order = localOrders[orderId] else { throw DomainError.orderNotFound(orderId) }
        guard qrVerifier.verify(order: order, scannedToken: scannedToken) else {
            throw DomainError.invalidOrderData("qrToken")
        }
        let next = try workflow.validateQr(order: order, scannedToken: scannedToken)
        localOrders[next.id] = next
        return try persistStatus(session: session, order: next)
    }

    public func completeAfterPayment(
        session: UserSession,
        order: DeliveryOrder,
        paymentReference: String
    ) throws -> DeliveryOrder {
        try ensureAuthenticated(session)
        guard let current = localOrders[order.id] else { throw DomainError.orderNotFound(order.id) }
        guard paymentGateway.validate(orderId: current.id, paymentReference: paymentReference) else {
            throw DomainError.paymentValidationFailed(paymentReference)
        }
        let completed = try workflow.completeAfterPayment(order: current, paymentReference: paymentReference)
        localOrders[completed.id] = completed
        let persisted = try persistStatus(session: session, order: completed)
        whatsAppNotifier.notifyClient(orderNumber: persisted.orderNumber, recipientPhoneNumber: persisted.recipientPhoneNumber)
        return persisted
    }

    public func syncOfflineQueue(session: UserSession) throws -> [DeliveryOrder] {
        try ensureAuthenticated(session)
        guard connectivity.isOnlineValue else { throw DomainError.networkUnavailable }

        for action in offlineQueue.drain() {
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
        if connectivity.isOnlineValue {
            let remote = try api.listOrders(session: session)
            remote.forEach { localOrders[$0.id] = $0 }
        }
        return localOrders.values.filter { $0.status != .completed && $0.status != .failed }
    }

    private func persistStatus(session: UserSession, order: DeliveryOrder) throws -> DeliveryOrder {
        if connectivity.isOnlineValue {
            var updated = try api.updateOrderStatus(
                session: session,
                orderId: order.id,
                status: order.status,
                courierId: order.courierId,
                paymentReference: order.paymentReference
            )
            if updated.qrToken == nil {
                updated.qrToken = order.qrToken
            }
            localOrders[updated.id] = updated
            return updated
        }

        offlineQueue.enqueue(.updateStatus(
            orderId: order.id,
            status: order.status,
            courierId: order.courierId,
            paymentReference: order.paymentReference
        ))
        return order
    }

    private func ensureAuthenticated(_ session: UserSession) throws {
        if session.authToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DomainError.unauthorized
        }
    }

    private func validate(_ order: DeliveryOrder) throws {
        if order.packageType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DomainError.invalidOrderData("packageType")
        }
        if order.weightKg <= 0 { throw DomainError.invalidOrderData("weightKg") }
        if order.volumeM3 <= 0 { throw DomainError.invalidOrderData("volumeM3") }
        if order.collectionLocation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DomainError.invalidOrderData("collectionLocation")
        }
        if order.deliveryAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DomainError.invalidOrderData("deliveryAddress")
        }
        if order.packageValue <= 0 { throw DomainError.invalidOrderData("packageValue") }
        if order.recipientPhoneNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DomainError.invalidOrderData("recipientPhoneNumber")
        }
    }
}

public final class InMemoryConnectivityMonitor: ConnectivityChecking {
    public var isOnlineValue: Bool
    public init(isOnlineValue: Bool = true) {
        self.isOnlineValue = isOnlineValue
    }
    public func setOnline(_ value: Bool) {
        isOnlineValue = value
    }
}

public final class InMemoryDolibarrApi: DolibarrAPI {
    private var orders: [UUID: DeliveryOrder] = [:]

    public init() {}

    public func createOrder(session: UserSession, order: DeliveryOrder) throws -> DeliveryOrder {
        var created = order
        created.updatedAt = Date()
        orders[created.id] = created
        return created
    }

    public func updateOrderStatus(
        session: UserSession,
        orderId: UUID,
        status: OrderStatus,
        courierId: String?,
        paymentReference: String?
    ) throws -> DeliveryOrder {
        guard var existing = orders[orderId] else { throw DomainError.orderNotFound(orderId) }
        existing.status = status
        if let courierId { existing.courierId = courierId }
        if let paymentReference { existing.paymentReference = paymentReference }
        existing.updatedAt = Date()
        orders[orderId] = existing
        return existing
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

public final class InMemoryPaymentGateway: AirtelPaymentValidating {
    private let validReferences: Set<String>
    public init(validReferences: Set<String>) {
        self.validReferences = validReferences
    }
    public func validate(orderId: UUID, paymentReference: String) -> Bool {
        validReferences.contains(paymentReference) || validReferences.contains("\(orderId.uuidString)|\(paymentReference)")
    }
}

public final class InMemoryQrVerifier: QrVerifying {
    public init() {}
    public func generate(orderId: UUID, recipientPhoneNumber: String) -> String {
        QrService.generateToken(orderId: orderId, recipientPhoneNumber: recipientPhoneNumber)
    }
    public func verify(order: DeliveryOrder, scannedToken: String) -> Bool {
        guard let token = order.qrToken else { return false }
        return token == scannedToken
    }
}
