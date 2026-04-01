package com.impala.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impala.app.data.DemoConnectivityMonitor
import com.impala.core.DeliveryOrder
import com.impala.core.DeliveryType
import com.impala.core.OrderRepository
import com.impala.core.OrderStatus
import com.impala.core.UserSession
import com.impala.core.generateOrderNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImpalaUiState(
    val orders: List<OrderItem> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isOnline: Boolean = true,
    val packageType: String = "Documents",
    val weightKg: String = "1.5",
    val volumeM3: String = "0.02",
    val collectionLocation: String = "Kinshasa Gombe",
    val deliveryAddress: String = "Limete 7e rue",
    val recipientPhone: String = "+243900000000",
    val packageValue: String = "120.0",
    val deliveryType: DeliveryType = DeliveryType.IMMEDIATE
)

data class OrderItem(
    val id: String,
    val orderNumber: String,
    val packageType: String,
    val recipientPhone: String,
    val status: OrderStatus,
    val statusLabel: String,
    val deliveryTypeLabel: String,
    val qrToken: String? = null
)

class ImpalaViewModel(
    private val repository: OrderRepository,
    private val session: UserSession,
    private val connectivity: DemoConnectivityMonitor
) : ViewModel() {
    private val _state = MutableStateFlow(ImpalaUiState(isOnline = connectivity.isOnline()))
    val state: StateFlow<ImpalaUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onPackageTypeChanged(value: String) {
        _state.value = _state.value.copy(packageType = value)
    }

    fun onWeightChanged(value: String) {
        _state.value = _state.value.copy(weightKg = value)
    }

    fun onVolumeChanged(value: String) {
        _state.value = _state.value.copy(volumeM3 = value)
    }

    fun onCollectionChanged(value: String) {
        _state.value = _state.value.copy(collectionLocation = value)
    }

    fun onAddressChanged(value: String) {
        _state.value = _state.value.copy(deliveryAddress = value)
    }

    fun onPhoneChanged(value: String) {
        _state.value = _state.value.copy(recipientPhone = value)
    }

    fun onValueChanged(value: String) {
        _state.value = _state.value.copy(packageValue = value)
    }

    fun onDeliveryTypeChanged(type: DeliveryType) {
        _state.value = _state.value.copy(deliveryType = type)
    }

    fun consumeError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun consumeInfo() {
        _state.value = _state.value.copy(infoMessage = null)
    }

    fun toggleConnectivity() {
        connectivity.setOnline(!connectivity.isOnline())
        _state.value = _state.value.copy(isOnline = connectivity.isOnline())
        if (connectivity.isOnline()) refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                errorMessage = null,
                infoMessage = null,
                isOnline = connectivity.isOnline()
            )
            runCatching {
                if (connectivity.isOnline()) {
                    repository.syncOfflineQueue(session)
                } else {
                    repository.orderHistory(session)
                }
            }.onSuccess { orders ->
                _state.value = _state.value.copy(
                    loading = false,
                    orders = orders.map { it.toUi() },
                    infoMessage = if (connectivity.isOnline()) "Synchronisation terminee." else "Mode hors-ligne actif."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(loading = false, errorMessage = error.message)
            }
        }
    }

    fun registerOrder() {
        viewModelScope.launch {
            val current = _state.value
            val order = DeliveryOrder(
                orderNumber = generateOrderNumber(),
                packageType = current.packageType,
                weightKg = current.weightKg.toDoubleOrNull() ?: -1.0,
                volumeM3 = current.volumeM3.toDoubleOrNull() ?: -1.0,
                collectionLocation = current.collectionLocation,
                deliveryAddress = current.deliveryAddress,
                recipientPhoneNumber = current.recipientPhone,
                deliveryType = current.deliveryType,
                packageValue = current.packageValue.toDoubleOrNull() ?: -1.0
            )
            runCatching {
                repository.registerOrder(session, order)
                repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = _state.value.copy(
                    orders = orders.map { it.toUi() },
                    infoMessage = if (connectivity.isOnline()) {
                        "Commande ${order.orderNumber} creee et synchronisee."
                    } else {
                        "Commande ${order.orderNumber} enregistree hors-ligne."
                    }
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message)
            }
        }
    }

    fun courierTakeOrder(orderId: String) {
        viewModelScope.launch {
            val order = repository.orderHistory(session).firstOrNull { it.id == orderId } ?: return@launch
            if (order.status != OrderStatus.PENDING) return@launch
            runCatching {
                repository.courierTakeOrder(session, order.id)
                repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = _state.value.copy(
                    orders = orders.map { it.toUi() },
                    infoMessage = "Commande ${order.orderNumber} prise par le livreur."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message)
            }
        }
    }

    fun requestQrValidation(orderId: String) {
        viewModelScope.launch {
            val order = repository.orderHistory(session).firstOrNull { it.id == orderId } ?: return@launch
            if (order.status != OrderStatus.IN_PROGRESS) return@launch
            runCatching {
                repository.requestQrValidation(session, order.id)
                repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = _state.value.copy(
                    orders = orders.map { it.toUi() },
                    infoMessage = "QR genere pour ${order.orderNumber}."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message)
            }
        }
    }

    fun verifyQr(orderId: String, scannedToken: String) {
        viewModelScope.launch {
            val order = repository.orderHistory(session).firstOrNull { it.id == orderId } ?: return@launch
            if (order.status != OrderStatus.WAITING_QR_VALIDATION) return@launch
            runCatching {
                repository.verifyQrAndRequestPayment(session, order.id, scannedToken)
                repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = _state.value.copy(
                    orders = orders.map { it.toUi() },
                    infoMessage = "QR valide pour ${order.orderNumber}, attente paiement Airtel Money."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message)
            }
        }
    }

    fun confirmPayment(orderId: String, paymentReference: String) {
        viewModelScope.launch {
            val order = repository.orderHistory(session).firstOrNull { it.id == orderId } ?: return@launch
            if (order.status != OrderStatus.WAITING_PAYMENT) return@launch
            runCatching {
                repository.completeAfterPayment(
                    session = session,
                    order = order,
                    paymentReference = paymentReference
                )
                repository.orderHistory(session)
            }.onSuccess { orders ->
                _state.value = _state.value.copy(
                    orders = orders.map { it.toUi() },
                    infoMessage = "Commande ${order.orderNumber} livree, paiement valide et WhatsApp envoye."
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(errorMessage = error.message)
            }
        }
    }

    private fun DeliveryOrder.toUi(): OrderItem {
        return OrderItem(
            id = id,
            orderNumber = orderNumber,
            packageType = packageType,
            recipientPhone = recipientPhoneNumber,
            status = status,
            statusLabel = when (status) {
                OrderStatus.PENDING -> "En attente"
                OrderStatus.IN_PROGRESS -> "En cours"
                OrderStatus.WAITING_QR_VALIDATION -> "En attente QR"
                OrderStatus.WAITING_PAYMENT -> "En attente paiement"
                OrderStatus.COMPLETED -> "Completee"
                OrderStatus.FAILED -> "Echouee"
            },
            deliveryTypeLabel = when (deliveryType) {
                DeliveryType.URGENT -> "Urgent"
                DeliveryType.IMMEDIATE -> "Immediat"
                DeliveryType.NORMAL -> "Normal"
                DeliveryType.DEFERRED -> "Differe"
            },
            qrToken = qrToken
        )
    }
}
