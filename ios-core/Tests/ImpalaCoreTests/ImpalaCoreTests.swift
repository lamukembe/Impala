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
        let api = InMemoryDolibarrAPI()
        let connectivity = InMemoryConnectivity(isOnline: false)
        let queue = OfflineQueue()
        let pushNotifier = InMemoryPushNotifier()
        let repository = OrderRepository(
            api: api,
            workflow: OrderWorkflow(),
            pushNotifier: pushNotifier,
            whatsAppNotifier: InMemoryWhatsAppNotifier(),
            paymentValidator: InMemoryPaymentValidator(validReferences: []),
            qrVerifier: DefaultQrVerifier(),
            connectivity: connectivity,
            offlineQueue: queue
        )

        let session = UserSession(userId: "courier-01", role: "courier", authToken: "token")
        _ = try repository.registerOrder(session: session, order: baseOrder(orderNumber: "IMP-2002"))
        XCTAssertEqual(queue.count, 1)

        connectivity.isOnline = true
        _ = try repository.syncOfflineQueue(session: session)
        XCTAssertEqual(queue.count, 0)
        XCTAssertEqual(pushNotifier.notifications.count, 1)
    }

    func testFullDeliveryFlowCompletesAndNotifiesWhatsApp() throws {
        let whatsApp = InMemoryWhatsAppNotifier()
        let repository = OrderRepository(
            api: InMemoryDolibarrAPI(),
            workflow: OrderWorkflow(),
            pushNotifier: InMemoryPushNotifier(),
            whatsAppNotifier: whatsApp,
            paymentValidator: InMemoryPaymentValidator(validReferences: ["AM-OK"]),
            qrVerifier: DefaultQrVerifier(),
            connectivity: InMemoryConnectivity(isOnline: true),
            offlineQueue: OfflineQueue()
        )

        let session = UserSession(userId: "courier-02", role: "courier", authToken: "token")
        let created = try repository.registerOrder(session: session, order: baseOrder())
        let inProgress = try repository.courierTakeOrder(session: session, orderId: created.id, courierId: session.userId)
        let waitingQr = try repository.requestQrValidation(session: session, orderId: inProgress.id)
        let waitingPayment = try repository.verifyQrAndRequestPayment(
            session: session,
            orderId: waitingQr.id,
            scannedToken: waitingQr.qrToken ?? ""
        )
        let completed = try repository.completeAfterPayment(
            session: session,
            orderId: waitingPayment.id,
            paymentReference: "AM-OK"
        )

        XCTAssertEqual(completed.status, .completed)
        XCTAssertEqual(completed.paymentReference, "AM-OK")
        XCTAssertEqual(whatsApp.notifications.count, 1)
    }

    func testInvalidPaymentThrows() throws {
        let repository = OrderRepository(
            api: InMemoryDolibarrAPI(),
            workflow: OrderWorkflow(),
            pushNotifier: InMemoryPushNotifier(),
            whatsAppNotifier: InMemoryWhatsAppNotifier(),
            paymentValidator: InMemoryPaymentValidator(validReferences: []),
            qrVerifier: DefaultQrVerifier(),
            connectivity: InMemoryConnectivity(isOnline: true),
            offlineQueue: OfflineQueue()
        )

        let session = UserSession(userId: "courier-03", role: "courier", authToken: "token")
        let created = try repository.registerOrder(session: session, order: baseOrder(orderNumber: "IMP-2003"))
        let inProgress = try repository.courierTakeOrder(session: session, orderId: created.id, courierId: session.userId)
        let waitingQr = try repository.requestQrValidation(session: session, orderId: inProgress.id)
        let waitingPayment = try repository.verifyQrAndRequestPayment(
            session: session,
            orderId: waitingQr.id,
            scannedToken: waitingQr.qrToken ?? ""
        )

        XCTAssertThrowsError(
            try repository.completeAfterPayment(
                session: session,
                orderId: waitingPayment.id,
                paymentReference: "AM-BAD"
            )
        ) { error in
            guard case ImpalaError.paymentValidationFailed = error else {
                XCTFail("Unexpected error: \(error)")
                return
            }
        }
    }
}
