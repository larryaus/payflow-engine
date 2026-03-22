const { Router } = require('express');
const paymentService = require('../services/paymentService');
const refundService = require('../services/refundService');
const idempotency = require('../middleware/idempotency');

const router = Router();

/**
 * POST /api/v1/payments
 * Create a new payment. Requires Idempotency-Key header.
 * Returns 202 Accepted with the payment data.
 */
router.post('/api/v1/payments', idempotency(), async (req, res, next) => {
  try {
    const payment = await paymentService.createPayment(req.body);
    res.status(202).json(payment);
  } catch (err) {
    next(err);
  }
});

/**
 * GET /api/v1/payments
 * List payments with pagination.
 * Query params: page (default 1), size (default 20)
 */
router.get('/api/v1/payments', async (req, res, next) => {
  try {
    const { page, size } = req.query;
    const result = await paymentService.listPayments(page, size);
    res.json(result);
  } catch (err) {
    next(err);
  }
});

/**
 * GET /api/v1/payments/:paymentId
 * Get a single payment by ID.
 */
router.get('/api/v1/payments/:paymentId', async (req, res, next) => {
  try {
    const payment = await paymentService.getPaymentById(req.params.paymentId);
    res.json(payment);
  } catch (err) {
    next(err);
  }
});

/**
 * POST /api/v1/payments/:paymentId/refund
 * Create a refund for a completed payment. Requires Idempotency-Key header.
 */
router.post('/api/v1/payments/:paymentId/refund', idempotency(), async (req, res, next) => {
  try {
    const refund = await refundService.createRefund(req.params.paymentId, req.body);
    res.status(201).json(refund);
  } catch (err) {
    next(err);
  }
});

module.exports = router;
