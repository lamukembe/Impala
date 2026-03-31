package com.impala.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.impala.app.viewmodel.ImpalaUiState
import com.impala.app.viewmodel.OrderItem
import com.impala.app.viewmodel.ImpalaViewModel
import com.impala.core.DeliveryType
import com.impala.core.OrderStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpalaApp(viewModel: ImpalaViewModel) {
    val uiState by viewModel.state.collectAsState()
    val snackBarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.errorMessage, uiState.infoMessage) {
        uiState.errorMessage?.let { message ->
            coroutineScope.launch {
                snackBarHostState.showSnackbar(message)
                viewModel.consumeError()
            }
        }
        uiState.infoMessage?.let { message ->
            coroutineScope.launch {
                snackBarHostState.showSnackbar(message)
                viewModel.consumeInfo()
            }
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("IMPALA") }) },
            snackbarHost = { SnackbarHost(hostState = snackBarHostState) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Nouvelle commande",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                NewOrderForm(
                    state = uiState,
                    onPackageTypeChange = viewModel::onPackageTypeChanged,
                    onWeightChange = viewModel::onWeightChanged,
                    onVolumeChange = viewModel::onVolumeChanged,
                    onCollectionChange = viewModel::onCollectionChanged,
                    onAddressChange = viewModel::onAddressChanged,
                    onPhoneChange = viewModel::onPhoneChanged,
                    onValueChange = viewModel::onValueChanged,
                    onDeliveryTypeChange = viewModel::onDeliveryTypeChanged,
                    onSubmit = viewModel::registerOrder
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Historique des commandes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = { viewModel.toggleConnectivity() }) {
                        Text(if (uiState.isOnline) "Passer hors-ligne" else "Revenir en ligne")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (uiState.isOnline) "Statut reseau: en ligne" else "Statut reseau: hors-ligne",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(8.dp))
                OrderHistoryList(
                    state = uiState,
                    onTakeOrder = viewModel::courierTakeOrder,
                    onAskQr = viewModel::requestQrValidation,
                    onScanQr = viewModel::verifyQr,
                    onConfirmPayment = viewModel::confirmPayment
                )
            }
        }
    }
}

@Composable
private fun NewOrderForm(
    state: ImpalaUiState,
    onPackageTypeChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onVolumeChange: (String) -> Unit,
    onCollectionChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onDeliveryTypeChange: (DeliveryType) -> Unit,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.packageType,
            onValueChange = onPackageTypeChange,
            label = { Text("Type de colis") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.weightKg,
                onValueChange = onWeightChange,
                label = { Text("Poids (kg)") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = state.volumeM3,
                onValueChange = onVolumeChange,
                label = { Text("Volume (m3)") },
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = state.collectionLocation,
            onValueChange = onCollectionChange,
            label = { Text("Lieu de collecte") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.deliveryAddress,
            onValueChange = onAddressChange,
            label = { Text("Adresse de livraison") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.recipientPhone,
            onValueChange = onPhoneChange,
            label = { Text("Telephone destinataire") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.packageValue,
            onValueChange = onValueChange,
            label = { Text("Valeur colis") },
            modifier = Modifier.fillMaxWidth()
        )
        DeliveryTypeSelector(
            selected = state.deliveryType,
            onSelect = onDeliveryTypeChange
        )
        Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) {
            Text("Enregistrer commande")
        }
    }
}

@Composable
private fun DeliveryTypeSelector(selected: DeliveryType, onSelect: (DeliveryType) -> Unit) {
    val allTypes = listOf(
        DeliveryType.URGENT,
        DeliveryType.IMMEDIATE,
        DeliveryType.NORMAL,
        DeliveryType.DEFERRED
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        allTypes.forEach { type ->
            Button(
                onClick = { onSelect(type) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    when (type) {
                        DeliveryType.URGENT -> "Urgent"
                        DeliveryType.IMMEDIATE -> "Immediat"
                        DeliveryType.NORMAL -> "Normal"
                        DeliveryType.DEFERRED -> "Differe"
                    } + if (type == selected) " *" else ""
                )
            }
        }
    }
}

@Composable
private fun OrderHistoryList(
    state: ImpalaUiState,
    onTakeOrder: (String) -> Unit,
    onAskQr: (String) -> Unit,
    onScanQr: (String, String) -> Unit,
    onConfirmPayment: (String, String) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.orders, key = { it.id }) { order ->
            OrderCard(
                order = order,
                onTakeOrder = { onTakeOrder(order.id) },
                onAskQr = { onAskQr(order.id) },
                onScanQr = { scanned -> onScanQr(order.id, scanned) },
                onConfirmPayment = { reference -> onConfirmPayment(order.id, reference) }
            )
        }
    }
}

@Composable
private fun OrderCard(
    order: OrderItem,
    onTakeOrder: () -> Unit,
    onAskQr: () -> Unit,
    onScanQr: (String) -> Unit,
    onConfirmPayment: (String) -> Unit
) {
    var showQrDialog by remember { mutableStateOf(false) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var scannedToken by remember { mutableStateOf("") }
    var paymentReference by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Commande #${order.orderNumber}", fontWeight = FontWeight.Bold)
            Text("Type: ${order.packageType} | Livraison: ${order.deliveryTypeLabel}")
            Text("Destinataire: ${order.recipientPhone}")
            Text("Statut: ${order.statusLabel}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (order.status == OrderStatus.PENDING) {
                    Button(onClick = onTakeOrder, modifier = Modifier.weight(1f)) {
                        Text("Prendre")
                    }
                }
                if (order.status == OrderStatus.IN_PROGRESS) {
                    Button(onClick = onAskQr, modifier = Modifier.weight(1f)) {
                        Text("Generer QR")
                    }
                }
                if (order.status == OrderStatus.WAITING_QR_VALIDATION) {
                    Button(onClick = { showQrDialog = true }, modifier = Modifier.weight(1f)) {
                        Text("Scanner QR")
                    }
                }
                if (order.status == OrderStatus.WAITING_PAYMENT) {
                    Button(onClick = { showPaymentDialog = true }, modifier = Modifier.weight(1f)) {
                        Text("Valider paiement")
                    }
                }
            }
        }
    }

    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("Verification QR") },
            text = {
                OutlinedTextField(
                    value = scannedToken,
                    onValueChange = { scannedToken = it },
                    label = { Text("Token QR scanne") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onScanQr(scannedToken)
                    showQrDialog = false
                    scannedToken = ""
                }) { Text("Verifier") }
            },
            dismissButton = {
                TextButton(onClick = { showQrDialog = false }) { Text("Annuler") }
            }
        )
    }

    if (showPaymentDialog) {
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text("Validation paiement Airtel Money") },
            text = {
                OutlinedTextField(
                    value = paymentReference,
                    onValueChange = { paymentReference = it },
                    label = { Text("Reference paiement") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onConfirmPayment(paymentReference)
                    showPaymentDialog = false
                    paymentReference = ""
                }) { Text("Valider") }
            },
            dismissButton = {
                TextButton(onClick = { showPaymentDialog = false }) { Text("Annuler") }
            }
        )
    }
}
