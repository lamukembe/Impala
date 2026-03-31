package com.impala.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowTest {
    private val workflow = OrderWorkflow()
    private val baseOrder = DeliveryOrder(
        orderNumber = "IMP-1001",
        packageType = "Documents",
        weightKg = 1.2,
        volumeM3 = 0.01,
        collectionLocation = "Kinshasa Gombe",
        deliveryAddress = "Limete 7e rue",
        recipientPhoneNumber = "+243900000000",
        deliveryType = DeliveryType.IMMEDIATE,
        packageValue = 100.0
    )

    @Test
    fun `un livreur peut prendre une commande pending`() {
        val updated = workflow.assignCourier(baseOrder, courierId = "courier-1")
        assertEquals(OrderStatus.IN_PROGRESS, updated.status)
        assertEquals("courier-1", updated.courierId)
    }

    @Test
    fun `une commande in progress peut attendre le QR`() {
        val inProgress = workflow.assignCourier(baseOrder, courierId = "courier-1")
        val qrWait = workflow.startQrValidation(inProgress)
        assertEquals(OrderStatus.WAITING_QR_VALIDATION, qrWait.status)
    }

    @Test
    fun `verification qr valide ouvre etape paiement`() {
        val inProgress = workflow.assignCourier(baseOrder, courierId = "courier-1")
        val qrWait = workflow.startQrValidation(inProgress)
        val afterQr = workflow.validateQr(qrWait, qrWait.qrToken.orEmpty())
        assertEquals(OrderStatus.WAITING_PAYMENT, afterQr.status)
    }

    @Test
    fun `qr invalide echoue`() {
        val inProgress = workflow.assignCourier(baseOrder, courierId = "courier-1")
        val qrWait = workflow.startQrValidation(inProgress)
        assertFailsWith<DomainError.InvalidOrderData> {
            workflow.validateQr(qrWait, "wrong")
        }
    }

    @Test
    fun `paiement valide finalise commande`() {
        val inProgress = workflow.assignCourier(baseOrder, courierId = "courier-1")
        val qrWait = workflow.startQrValidation(inProgress)
        val afterQr = workflow.validateQr(qrWait, qrWait.qrToken.orEmpty())
        val completed = workflow.completeAfterPayment(afterQr, paymentReference = "AM-123")
        assertEquals(OrderStatus.COMPLETED, completed.status)
        assertEquals("AM-123", completed.paymentReference)
    }
}
