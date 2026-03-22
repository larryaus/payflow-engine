const { Router } = require('express');
const paymentService = require('../services/paymentService');
const refundService = require('../services/refundService');
const idempotency = require('../middleware/idempotency');

const router = Router();

// Create payment
router.post('/', idempotency(), async (req, res, next) => {
  try {
    const result = await paymentService.createPayment(
      req.body,
      req.idempotencyKey
    );
    res.status(201).json(result);
  } catch (err) {
    next(err);
  }
});

// List payments
router.get('/', async (req, res, next) => {
  try {
    const page = parseInt(req.query.page) || 1;
    const size = parseInt(req.query.size) || 20;
    const result = await paymentService.listPayments(page, size);
    res.json(result);
  } catch (err) {
    next(err);
  }
});

// Get payment by ID
router.get('/:paymentId', async (req, res, next) => {
  try {
    const payment = await paymentService.getPaymentById(req.params.paymentId);
    res.json(payment);
  } catch (err) {
    next(err);
  }
});

// Create refund
router.post('/:paymentId/refund', idempotency(), async (req, res, next) => {
  try {
    const result = await refundService.createRefund(
      req.params.paymentId,
      req.body,
      req.idempotencyKey
    );
    res.status(201).json(result);
  } catch (err) {
    next(err);
  }
});

module.exports = router;
