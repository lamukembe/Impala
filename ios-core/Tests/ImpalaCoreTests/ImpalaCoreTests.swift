import XCTest
@testable import ImpalaCore

final class ImpalaCoreTests: XCTestCase {
    private func baseOrder(orderNumber: String = "IMP-2001") -> DeliveryOrder {
        DeliveryOrder(
            orderNumber: orderNumber,
            packageType: "Documents",
            weightKg: 1.5,
            volumeM3: 0.02,
            collectionLocation: "Kinshasa Gombe",
            deliveryAddress: "Limete 7e rue",
            recipientPhoneNumber: "+243900000000",
            deliveryType: .normal,
            packageValue: 80
        )
    }

    func testRegisterOrderOfflineQueuesThenSyncs() throws {
        let api = InMemoryDolibarrApi()
        let connectivity = InMemoryConnectivityMonitor(isOnlineValue: false)
        let queue = OfflineSyncQueue()
        let repository = OrderRepository(
            api: api,
            workflow: OrderWorkflow(),
            connectivity: connectivity,
            offlineQueue: queue,
            pushNotifier: InMemoryPushNotifier(),
            qrVerifier: InMemoryQrVerifier(),
            paymentGateway: InMemoryPaymentGateway(validReferences: []),
            whatsAppNotifier: InMemoryWhatsAppNotifier()
        )

        let session = UserSession(userId: "courier-01", role: "courier", authToken: "token")
        _ = try repository.registerOrder(session: session, order: baseOrder(orderNumber: "IMP-2002"))
        XCTAssertEqual(queue.size, 1)

        connectivity.isOnlineValue = true
        _ = try repository.syncOfflineQueue(session: session)
        XCTAssertEqual(queue.size, 0)
    }

    func testFullDeliveryFlowCompletesAndNotifiesWhatsApp() throws {
        let repository = OrderRepository(
            api: InMemoryDolibarrApi(),
            workflow: OrderWorkflow(),
            connectivity: InMemoryConnectivityMonitor(isOnlineValue: true),
            offlineQueue: OfflineSyncQueue(),
            pushNotifier: InMemoryPushNotifier(),
            qrVerifier: InMemoryQrVerifier(),
            paymentGateway: InMemoryPaymentGateway(validReferences: ["AM-OK"]),
            whatsAppNotifier: InMemoryWhatsAppNotifier()
        )

        let session = UserSession(userId: "courier-02", role: "courier", authToken: "token")
        let created = try repository.registerOrder(session: session, order: baseOrder())
        let inProgress = try repository.courierTakeOrder(session: session, orderId: created.id)
        let waitingQr = try repository.requestQrValidation(session: session, orderId: inProgress.id)
        let waitingPayment = try repository.verifyQrAndRequestPayment(
            session: session,
            orderId: waitingQr.id,
            scannedToken: waitingQr.qrToken ?? ""
        )
        let completed = try repository.completeAfterPayment(
            session: session,
            order: waitingPayment,
            paymentReference: "AM-OK"
        )

        XCTAssertEqual(completed.status, .completed)
        XCTAssertEqual(completed.paymentReference, "AM-OK")
    }

    func testInvalidPaymentThrows() throws {
        let repository = OrderRepository(
            api: InMemoryDolibarrApi(),
            workflow: OrderWorkflow(),
            connectivity: InMemoryConnectivityMonitor(isOnlineValue: true),
            offlineQueue: OfflineSyncQueue(),
            pushNotifier: InMemoryPushNotifier(),
            qrVerifier: InMemoryQrVerifier(),
            paymentGateway: InMemoryPaymentGateway(validReferences: []),
            whatsAppNotifier: InMemoryWhatsAppNotifier()
        )

        let session = UserSession(userId: "courier-03", role: "courier", authToken: "token")
        let created = try repository.registerOrder(session: session, order: baseOrder(orderNumber: "IMP-2003"))
        let inProgress = try repository.courierTakeOrder(session: session, orderId: created.id)
        let waitingQr = try repository.requestQrValidation(session: session, orderId: inProgress.id)
        let waitingPayment = try repository.verifyQrAndRequestPayment(
            session: session,
            orderId: waitingQr.id,
            scannedToken: waitingQr.qrToken ?? ""
        )

        XCTAssertThrowsError(
            try repository.completeAfterPayment(
                session: session,
                order: waitingPayment,
                paymentReference: "AM-BAD"
            )
        ) { error in
            guard case DomainError.paymentValidationFailed = error else {
                XCTFail("Unexpected error: \(error)")
                return
            }
        }
    }
}
