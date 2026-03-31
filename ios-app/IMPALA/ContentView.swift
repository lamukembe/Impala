import SwiftUI
import ImpalaCore

struct ContentView: View {
    @State private var orderType = "Document"
    @State private var weight = "1.2"
    @State private var volume = "0.02"
    @State private var collection = "Kinshasa Gombe"
    @State private var address = "Limete 7e rue"
    @State private var phone = "+243900000000"
    @State private var value = "120.0"
    @State private var deliveryType: DeliveryType = .normal
    @State private var courierId = "courier-ios-1"
    @State private var scannedQr = ""
    @State private var paymentReference = ""
    @State private var selectedOrderId: UUID?
    @State private var message = ""
    @State private var isOnline = true

    @StateObject private var viewModel = IOSOrderViewModel()

    var body: some View {
        NavigationStack {
            Form {
                Section("Connectivite") {
                    Toggle("Mode en ligne", isOn: $isOnline)
                        .onChange(of: isOnline) { value in
                            viewModel.setOnline(value)
                        }
                    Button("Synchroniser hors-ligne") {
                        runAction {
                            try viewModel.syncOffline()
                        }
                    }
                }

                Section("Nouvelle commande") {
                    TextField("Type de colis", text: $orderType)
                    TextField("Poids (kg)", text: $weight)
                        .keyboardType(.decimalPad)
                    TextField("Volume (m3)", text: $volume)
                        .keyboardType(.decimalPad)
                    TextField("Lieu de collecte", text: $collection)
                    TextField("Adresse de livraison", text: $address)
                    TextField("Telephone destinataire", text: $phone)
                    Picker("Type de livraison", selection: $deliveryType) {
                        ForEach(DeliveryType.allCases, id: \.self) { type in
                            Text(type.rawValue).tag(type)
                        }
                    }
                    TextField("Valeur colis", text: $value)
                        .keyboardType(.decimalPad)

                    Button("Enregistrer commande") {
                        runAction {
                            try viewModel.createOrder(
                                packageType: orderType,
                                weightKg: Double(weight) ?? 0,
                                volumeM3: Double(volume) ?? 0,
                                collectionLocation: collection,
                                deliveryAddress: address,
                                recipientPhone: phone,
                                deliveryType: deliveryType,
                                packageValue: Double(value) ?? 0
                            )
                        }
                    }
                }

                Section("Suivi livraison") {
                    Picker("Commande", selection: Binding(
                        get: { selectedOrderId },
                        set: { selectedOrderId = $0 }
                    )) {
                        Text("Selectionner").tag(UUID?.none)
                        ForEach(viewModel.orders, id: \.id) { order in
                            Text("\(order.orderNumber) - \(order.status.rawValue)").tag(UUID?.some(order.id))
                        }
                    }

                    TextField("Courier ID", text: $courierId)
                    Button("Prendre commande") {
                        runAction {
                            try withSelectedOrder { id in
                                try viewModel.takeOrder(orderId: id, courierId: courierId)
                            }
                        }
                    }

                    Button("Generer QR") {
                        runAction {
                            try withSelectedOrder { id in
                                try viewModel.requestQr(orderId: id)
                            }
                        }
                    }

                    TextField("QR scanne", text: $scannedQr)
                    Button("Valider QR") {
                        runAction {
                            try withSelectedOrder { id in
                                try viewModel.verifyQr(orderId: id, scannedToken: scannedQr)
                            }
                        }
                    }

                    TextField("Reference Airtel Money", text: $paymentReference)
                    Button("Valider paiement & terminer") {
                        runAction {
                            try withSelectedOrder { id in
                                try viewModel.completePayment(orderId: id, paymentReference: paymentReference)
                            }
                        }
                    }
                }

                if !message.isEmpty {
                    Section("Journal") {
                        Text(message)
                            .font(.footnote)
                    }
                }

                Section("Historique") {
                    ForEach(viewModel.orders, id: \.id) { order in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(order.orderNumber).font(.headline)
                            Text("Statut: \(order.status.rawValue)")
                            if let courier = order.courierId {
                                Text("Livreur: \(courier)")
                            }
                            if let qr = order.qrToken {
                                Text("QR: \(qr.prefix(10))...")
                                    .font(.caption)
                            }
                            if let payment = order.paymentReference {
                                Text("Paiement: \(payment)")
                                    .font(.caption)
                            }
                        }
                    }
                }
            }
            .navigationTitle("IMPALA iOS")
        }
    }

    private func runAction(_ action: () throws -> Void) {
        do {
            try action()
            message = "Action terminee avec succes."
        } catch {
            message = "Erreur: \(error.localizedDescription)"
        }
    }

    private func withSelectedOrder(_ action: (UUID) throws -> Void) throws {
        guard let selectedOrderId else {
            throw NSError(domain: "IMPALA", code: 1, userInfo: [NSLocalizedDescriptionKey: "Selectionnez une commande"])
        }
        try action(selectedOrderId)
    }
}

final class IOSOrderViewModel: ObservableObject {
    @Published var orders: [DeliveryOrder] = []

    private let session = UserSession(userId: "ios-user", role: "courier", authToken: "ios-token")
    private let connectivity = InMemoryConnectivity(isOnline: true)
    private let api = InMemoryDolibarrAPI()
    private let workflow = OrderWorkflow()
    private let queue = OfflineQueue()
    private let pushNotifier = InMemoryPushNotifier()
    private let qrVerifier = DefaultQrVerifier()
    private let paymentGateway = InMemoryPaymentValidator(validReferences: ["AM-IOS-1"])
    private let whatsAppNotifier = InMemoryWhatsAppNotifier()
    private lazy var repository = OrderRepository(
        api: api,
        workflow: workflow,
        connectivity: connectivity,
        offlineQueue: queue,
        pushNotifier: pushNotifier,
        qrVerifier: qrVerifier,
        paymentGateway: paymentGateway,
        whatsAppNotifier: whatsAppNotifier
    )

    func setOnline(_ value: Bool) {
        connectivity.setOnline(value)
    }

    func createOrder(
        packageType: String,
        weightKg: Double,
        volumeM3: Double,
        collectionLocation: String,
        deliveryAddress: String,
        recipientPhone: String,
        deliveryType: DeliveryType,
        packageValue: Double
    ) throws {
        let order = DeliveryOrder(
            orderNumber: generateOrderNumber(),
            packageType: packageType,
            weightKg: weightKg,
            volumeM3: volumeM3,
            collectionLocation: collectionLocation,
            deliveryAddress: deliveryAddress,
            recipientPhoneNumber: recipientPhone,
            deliveryType: deliveryType,
            packageValue: packageValue
        )
        _ = try repository.registerOrder(session: session, order: order)
        refresh()
    }

    func takeOrder(orderId: UUID, courierId: String) throws {
        _ = try repository.courierTakeOrder(session: session, orderId: orderId, courierId: courierId)
        refresh()
    }

    func requestQr(orderId: UUID) throws {
        _ = try repository.requestQrValidation(session: session, orderId: orderId)
        refresh()
    }

    func verifyQr(orderId: UUID, scannedToken: String) throws {
        _ = try repository.verifyQrAndRequestPayment(session: session, orderId: orderId, scannedToken: scannedToken)
        refresh()
    }

    func completePayment(orderId: UUID, paymentReference: String) throws {
        guard let order = orders.first(where: { $0.id == orderId }) else {
            throw NSError(domain: "IMPALA", code: 2, userInfo: [NSLocalizedDescriptionKey: "Commande introuvable"])
        }
        _ = try repository.completeAfterPayment(session: session, order: order, paymentReference: paymentReference)
        refresh()
    }

    func syncOffline() throws {
        _ = try repository.syncOfflineQueue(session: session)
        refresh()
    }

    private func refresh() {
        orders = repository.orderHistory(session: session)
    }
}
