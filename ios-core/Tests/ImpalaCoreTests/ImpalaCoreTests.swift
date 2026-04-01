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
        let connectivity = InMemoryConnectivity(isOnlineValue: false)
        let queue = OfflineQueue()
        let pushNotifier = InMemoryPushNotifier()
        let realtime = InMemoryRealtimeEventBus()
        let repository = OrderRepository(
            api: api,
            workflow: OrderWorkflow(),
            connectivity: connectivity,
            offlineQueue: queue,
            pushNotifier: pushNotifier,
            realtimePublisher: realtime,
            qrVerifier: InMemoryQrVerifier(),
            paymentGateway: InMemoryPaymentGateway(validReferences: []),
            whatsAppNotifier: InMemoryWhatsAppNotifier()
        )

        let session = UserSession(userId: "courier-01", role: "CLIENT", authToken: "token")
        _ = try repository.registerOrder(session: session, order: baseOrder(orderNumber: "IMP-2002"))
        XCTAssertEqual(queue.count, 1)

        connectivity.setOnline(true)
        _ = try repository.syncOfflineQueue(session: session)
        XCTAssertEqual(queue.count, 0)
        XCTAssertEqual(pushNotifier.notifications.count, 1)
        XCTAssertEqual(realtime.events.count, 2)
    }

    func testFullDeliveryFlowCompletesAndNotifiesWhatsApp() throws {
        let whatsApp = InMemoryWhatsAppNotifier()
        let realtime = InMemoryRealtimeEventBus()
        let repository = OrderRepository(
            api: InMemoryDolibarrApi(),
            workflow: OrderWorkflow(),
            connectivity: InMemoryConnectivity(isOnlineValue: true),
            offlineQueue: OfflineQueue(),
            pushNotifier: InMemoryPushNotifier(),
            realtimePublisher: realtime,
            qrVerifier: InMemoryQrVerifier(),
            paymentGateway: InMemoryPaymentGateway(validReferences: ["AM-OK"]),
            whatsAppNotifier: whatsApp
        )

        let client = UserSession(userId: "client-01", role: "CLIENT", authToken: "token")
        let session = UserSession(userId: "courier-02", role: "COURIER", authToken: "token")
        let created = try repository.registerOrder(session: client, order: baseOrder())
        let inProgress = try repository.courierTakeOrder(session: session, orderId: created.id, courierId: session.userId)
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
        XCTAssertEqual(completed.paymentReference, "TX-AM-OK")
        XCTAssertEqual(completed.paymentRecord?.amount, 80)
        XCTAssertEqual(whatsApp.notifications.count, 1)
        XCTAssertTrue(realtime.events.contains { $0.type == .deliveryCompleted })
    }

    func testInvalidPaymentThrows() throws {
        let repository = OrderRepository(
            api: InMemoryDolibarrApi(),
            workflow: OrderWorkflow(),
            connectivity: InMemoryConnectivity(isOnlineValue: true),
            offlineQueue: OfflineQueue(),
            pushNotifier: InMemoryPushNotifier(),
            realtimePublisher: InMemoryRealtimeEventBus(),
            qrVerifier: InMemoryQrVerifier(),
            paymentGateway: InMemoryPaymentGateway(validReferences: []),
            whatsAppNotifier: InMemoryWhatsAppNotifier()
        )

        let client = UserSession(userId: "client-03", role: "CLIENT", authToken: "token")
        let session = UserSession(userId: "courier-03", role: "COURIER", authToken: "token")
        let created = try repository.registerOrder(session: client, order: baseOrder(orderNumber: "IMP-2003"))
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

    func testTrackingUpdateAppendsHistoryAndEmitsRealtimeEvent() throws {
        let realtime = InMemoryRealtimeEventBus()
        let repository = OrderRepository(
            api: InMemoryDolibarrApi(),
            workflow: OrderWorkflow(),
            connectivity: InMemoryConnectivity(isOnlineValue: true),
            offlineQueue: OfflineQueue(),
            pushNotifier: InMemoryPushNotifier(),
            realtimePublisher: realtime,
            qrVerifier: InMemoryQrVerifier(),
            paymentGateway: InMemoryPaymentGateway(validReferences: ["AM-T"]),
            whatsAppNotifier: InMemoryWhatsAppNotifier()
        )

        let client = UserSession(userId: "client-t", role: "CLIENT", authToken: "token")
        let courier = UserSession(userId: "courier-t", role: "COURIER", authToken: "token")
        let created = try repository.registerOrder(session: client, order: baseOrder(orderNumber: "IMP-TRACK"))
        let inProgress = try repository.courierTakeOrder(session: courier, orderId: created.id, courierId: courier.userId)

        let tracked = try repository.updateTracking(
            session: courier,
            orderId: inProgress.id,
            latitude: -4.32,
            longitude: 15.30,
            speedKmh: 28,
            etaMinutes: 11
        )

        XCTAssertEqual(tracked.etaMinutes, 11)
        XCTAssertEqual(tracked.currentLocation?.latitude, -4.32)
        XCTAssertTrue(realtime.events.contains { $0.type == .trackingUpdated })
    }
}
