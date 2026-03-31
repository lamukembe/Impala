package com.impala.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impala.app.data.AppContainer
import com.impala.core.DeliveryOrder
import com.impala.core.DeliveryType
import com.impala.core.OrderStatus
import com.impala.core.generateOrderNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardState(
    val orders: List<DeliveryOrder> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null
)

class OrderViewModel(
    private val container: AppContainer = AppContainer.default()
) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val session = container.defaultSession

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, info = null)
            runCatching {
                container.repository.syncOfflineQueue(session)
                container.repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = DashboardState(orders = orders)
            }.onFailure { error ->
                _state.value = DashboardState(error = error.message)
            }
        }
    }

    fun createSampleOrder() {
        viewModelScope.launch {
            val order = DeliveryOrder(
                orderNumber = generateOrderNumber(),
                packageType = "Documents",
                weightKg = 1.5,
                volumeM3 = 0.02,
                collectionLocation = "Kinshasa Gombe",
                deliveryAddress = "Limete 7e rue",
                recipientPhoneNumber = "+243900000000",
                deliveryType = DeliveryType.IMMEDIATE,
                packageValue = 120.0
            )
            runCatching {
                container.repository.registerOrder(session, order)
                container.repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = DashboardState(
                    orders = orders,
                    info = "Commande ${order.orderNumber} creee et synchronisee."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(error = error.message)
            }
        }
    }

    fun takeOrder(order: DeliveryOrder) {
        if (order.status != OrderStatus.PENDING) return
        viewModelScope.launch {
            runCatching {
                container.repository.courierTakeOrder(session, order.id)
                container.repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = DashboardState(
                    orders = orders,
                    info = "Commande ${order.orderNumber} prise par le livreur."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(error = error.message)
            }
        }
    }

    fun startQr(order: DeliveryOrder) {
        if (order.status != OrderStatus.IN_PROGRESS) return
        viewModelScope.launch {
            runCatching {
                container.repository.requestQrValidation(session, order.id)
                container.repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = DashboardState(
                    orders = orders,
                    info = "QR genere pour ${order.orderNumber}."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(error = error.message)
            }
        }
    }

    fun scanAndRequestPayment(order: DeliveryOrder) {
        if (order.status != OrderStatus.WAITING_QR_VALIDATION || order.qrToken.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching {
                container.repository.verifyQrAndRequestPayment(session, order.id, order.qrToken)
                container.repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = DashboardState(
                    orders = orders,
                    info = "QR valide pour ${order.orderNumber}, attente paiement Airtel Money."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(error = error.message)
            }
        }
    }

    fun completePayment(order: DeliveryOrder) {
        if (order.status != OrderStatus.WAITING_PAYMENT) return
        viewModelScope.launch {
            runCatching {
                container.repository.completeAfterPayment(
                    session = session,
                    order = order,
                    paymentReference = "AM-${order.orderNumber.takeLast(4)}"
                )
                container.repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = DashboardState(
                    orders = orders,
                    info = "Commande ${order.orderNumber} livree, paiement valide et WhatsApp envoye."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(error = error.message)
            }
        }
    }
}
