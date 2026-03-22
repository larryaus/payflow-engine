const { v4: uuidv4 } = require('uuid');
const pool = require('../db');
const accountClient = require('../clients/accountClient');
const ledgerClient = require('../clients/ledgerClient');
const paymentService = require('./paymentService');
const { PaymentError, ERROR_TYPES } = require('../errors');

/**
 * Generate a refund ID: REF_YYYYMMDD_<8-char-uuid-substr>
 */
function generateRefundId() {
  const now = new Date();
  const dateStr =
    now.getFullYear().toString() +
    String(now.getMonth() + 1).padStart(2, '0') +
    String(now.getDate()).padStart(2, '0');
  const shortUuid = uuidv4().replace(/-/g, '').substring(0, 8);
  return `REF_${dateStr}_${shortUuid}`;
}

/**
 * Create a refund for a completed payment.
 *
 * Steps:
 * 1. Look up original payment, validate it is COMPLETED
 * 2. Validate refund amount <= payment amount
 * 3. Generate refundId
 * 4. Insert refund_order with status PROCESSING
 * 5. Reverse transfer (to_account -> from_account)
 * 6. Reverse ledger entry
 * 7. Update refund status to COMPLETED
 * 8. If full refund, update payment status to REFUNDED
 */
async function createRefund(paymentId, data) {
  // 1. Look up original payment
  const payment = await paymentService.getPaymentById(paymentId);

  if (payment.status !== 'COMPLETED') {
    throw new PaymentError(
      ERROR_TYPES.INVALID_STATUS,
      `Payment ${paymentId} is not in COMPLETED status (current: ${payment.status})`
    );
  }

  // 2. Validate refund amount
  const amount = data.amount || payment.amount;

  if (typeof amount !== 'number' || amount <= 0) {
    throw new PaymentError(ERROR_TYPES.INVALID_AMOUNT, 'Refund amount must be a positive number');
  }

  if (amount > payment.amount) {
    throw new PaymentError(
      ERROR_TYPES.INVALID_AMOUNT,
      `Refund amount (${amount}) exceeds payment amount (${payment.amount})`
    );
  }

  // 3. Generate refund ID
  const refundId = generateRefundId();
  const now = new Date();

  // 4. Insert refund_order with status PROCESSING
  await pool.query(
    `INSERT INTO refund_order
       (refund_id, payment_id, amount, currency, reason, status, created_at, updated_at)
     VALUES ($1, $2, $3, $4, $5, 'PROCESSING', $6, $6)`,
    [refundId, paymentId, amount, payment.currency, data.reason || '', now, now]
  );

  try {
    // 5. Reverse transfer (to_account -> from_account)
    await accountClient.transfer(payment.to_account, payment.from_account, amount);

    // 6. Reverse ledger entry
    await ledgerClient.createEntry(refundId, payment.to_account, payment.from_account, amount);

    // 7. Update refund status to COMPLETED
    await pool.query(
      `UPDATE refund_order SET status = 'COMPLETED', updated_at = NOW() WHERE refund_id = $1`,
      [refundId]
    );

    // 8. If full refund (amount == payment.amount), update payment status to REFUNDED
    if (amount === payment.amount) {
      await paymentService.updateStatus(null, paymentId, 'COMPLETED', 'REFUNDING');
      await paymentService.updateStatus(null, paymentId, 'REFUNDING', 'REFUNDED');
    }

    // Return refund record
    const result = await pool.query('SELECT * FROM refund_order WHERE refund_id = $1', [refundId]);
    return result.rows[0];
  } catch (err) {
    // Mark refund as failed
    await pool.query(
      `UPDATE refund_order SET status = 'FAILED', updated_at = NOW() WHERE refund_id = $1`,
      [refundId]
    );
    throw err;
  }
}

module.exports = { createRefund };
