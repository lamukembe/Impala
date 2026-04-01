package com.impala.app.data

import com.impala.core.OrderRepository
import com.impala.core.OrderWorkflow
import com.impala.core.OfflineSyncQueue
import com.impala.core.UserSession

class AppContainer {
    val connectivity = DemoConnectivityMonitor(online = true)
    val repository: OrderRepository
    val defaultSession = UserSession(
        userId = "courier-mobile-1",
        role = "courier",
        authToken = "demo-session-token"
    )

    init {
        repository = OrderRepository(
            api = DemoDolibarrApi(),
            workflow = OrderWorkflow(),
            connectivity = connectivity,
            offlineQueue = OfflineSyncQueue(),
            pushNotifier = DemoPushNotifier(),
            qrVerifier = DemoQrVerifier(),
            paymentGateway = DemoPaymentGateway(),
            whatsAppNotifier = DemoWhatsAppNotifier()
        )
    }

    companion object {
        fun default(): AppContainer = AppContainer()
    }
}
