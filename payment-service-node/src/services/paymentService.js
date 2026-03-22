const { v4: uuidv4 } = require('uuid');
const pool = require('../db');
const redis = require('../redis');
const { publishEvent } = require('../kafka');
const riskClient = require('../clients/riskClient');
const accountClient = require('../clients/accountClient');
const ledgerClient = require('../clients/ledgerClient');
const { PaymentError, ERROR_TYPES } = require('../errors');

// --- Payment State Machine ---

const VALID_TRANSITIONS = {
  CREATED: ['PENDING', 'REJECTED'],
  PENDING: ['PROCESSING', 'REJECTED'],
  PROCESSING: ['COMPLETED', 'FAILED'],
  COMPLETED: ['REFUNDING'],
  REFUNDING: ['REFUNDED'],
};

function canTransitTo(from, to) {
  const allowed = VALID_TRANSITIONS[from];
  return Array.isArray(allowed) && allowed.includes(to);
}

// --- Payment ID Generation ---

function generatePaymentId() {
  const now = new Date();
  const dateStr =
    now.getFullYear().toString() +
    String(now.getMonth() + 1).padStart(2, '0') +
    String(now.getDate()).padStart(2, '0');
  const shortUuid = uuidv4().replace(/-/g, '').substring(0, 8);
  return `PAY_${dateStr}_${shortUuid}`;
}

// --- Distributed Lock Helpers ---

const LOCK_TTL_SECONDS = 30;
const LOCK_RETRY_INTERVAL_MS = 500;
const LOCK_ACQUIRE_TIMEOUT_MS = 5000;

async function acquireLock(accountId) {
  const lockKey = `account:lock:${accountId}`;
  const lockValue = uuidv4();
  const deadline = Date.now() + LOCK_ACQUIRE_TIMEOUT_MS;

  while (Date.now() < deadline) {
    const acquired = await redis.set(lockKey, lockValue, 'EX', LOCK_TTL_SECONDS, 'NX');
    if (acquired) {
      return { lockKey, lockValue };
    }
    await new Promise((resolve) => setTimeout(resolve, LOCK_RETRY_INTERVAL_MS));
  }

  throw new PaymentError(
    ERROR_TYPES.ACCOUNT_BUSY,
    'Could not acquire account lock — account is busy'
  );
}

async function releaseLock(lockKey, lockValue) {
  // Lua script ensures we only delete our own lock
  const script = `
    if redis.call("get", KEYS[1]) == ARGV[1] then
      return redis.call("del", KEYS[1])
    else
      return 0
    end
  `;
  await redis.eval(script, 1, lockKey, lockValue);
}

// --- Status Update Helper ---

async function updateStatus(client, paymentId, fromStatus, toStatus, extraFields) {
  if (!canTransitTo(fromStatus, toStatus)) {
    throw new PaymentError(
      ERROR_TYPES.INVALID_STATUS,
      `Cannot transition from ${fromStatus} to ${toStatus}`
    );
  }

  let query = 'UPDATE payment_order SET status = $1, updated_at = NOW()';
  const params = [toStatus];
  let paramIndex = 2;

  if (extraFields) {
    for (const [column, value] of Object.entries(extraFields)) {
      query += `, ${column} = $${paramIndex}`;
      params.push(value);
      paramIndex++;
    }
  }

  query += ` WHERE payment_id = $${paramIndex} AND status = $${paramIndex + 1}`;
  params.push(paymentId, fromStatus);

  const result = await (client || pool).query(query, params);

  if (result.rowCount === 0) {
    throw new PaymentError(
      ERROR_TYPES.INVALID_STATUS,
      `Payment ${paymentId} is not in ${fromStatus} status`
    );
  }
}

// --- Public Methods ---

/**
 * Create a new payment.
 * Performs risk check synchronously, then publishes to Kafka for async processing.
 */
async function createPayment(data) {
  const { from_account, to_account, amount, currency, description } = data;

  if (!from_account || !to_account || amount == null) {
    throw new PaymentError(
      ERROR_TYPES.INVALID_AMOUNT,
      'from_account, to_account, and amount are required'
    );
  }

  if (typeof amount !== 'number' || amount <= 0) {
    throw new PaymentError(ERROR_TYPES.INVALID_AMOUNT, 'Amount must be a positive number');
  }

  const paymentId = generatePaymentId();
  const now = new Date();

  // 1. Insert payment with status CREATED
  await pool.query(
    `INSERT INTO payment_order
       (payment_id, from_account, to_account, amount, currency, description, status, created_at, updated_at)
     VALUES ($1, $2, $3, $4, $5, $6, 'CREATED', $7, $7)`,
    [paymentId, from_account, to_account, amount, currency || 'CNY', description || '', now, now]
  );

  // 2. Risk check (synchronous)
  const approved = await riskClient.checkRisk(from_account, to_account, amount);
  if (!approved) {
    await updateStatus(null, paymentId, 'CREATED', 'REJECTED');
    throw new PaymentError(ERROR_TYPES.RISK_REJECTED, 'Payment rejected by risk service');
  }

  // 3. Transition to PENDING
  await updateStatus(null, paymentId, 'CREATED', 'PENDING');

  // 4. Publish to Kafka for async processing
  const paymentData = {
    paymentId,
    from_account,
    to_account,
    amount,
    currency: currency || 'CNY',
    description: description || '',
  };

  await publishEvent('payment.created', from_account, paymentData);

  // 5. Return payment info
  const result = await pool.query('SELECT * FROM payment_order WHERE payment_id = $1', [
    paymentId,
  ]);

  return result.rows[0];
}

/**
 * Async payment processing triggered by Kafka consumer.
 * Acquires distributed lock, freezes funds, creates ledger entry, transfers, completes.
 */
async function processPaymentAsync(paymentData) {
  const { paymentId, from_account, to_account, amount } = paymentData;
  let lock = null;
  let frozen = false;

  try {
    // 1. Acquire distributed lock on source account
    lock = await acquireLock(from_account);

    // 2. PENDING → PROCESSING
    await updateStatus(null, paymentId, 'PENDING', 'PROCESSING');

    // 3. Freeze funds on source account
    await accountClient.freeze(from_account, amount);
    frozen = true;

    // 4. Create double-entry ledger record
    await ledgerClient.createEntry(paymentId, from_account, to_account, amount);

    // 5. Execute transfer
    await accountClient.transfer(from_account, to_account, amount);

    // 6. PROCESSING → COMPLETED
    await updateStatus(null, paymentId, 'PROCESSING', 'COMPLETED', {
      completed_at: new Date(),
    });

    // 7. Publish completion event
    await publishEvent('payment.completed', from_account, {
      paymentId,
      from_account,
      to_account,
      amount,
      status: 'COMPLETED',
    });

    console.log(`Payment ${paymentId} completed successfully`);
  } catch (err) {
    console.error(`Payment ${paymentId} processing failed:`, err.message);

    // Rollback: unfreeze if we had frozen
    if (frozen) {
      try {
        await accountClient.unfreeze(from_account, amount);
      } catch (unfreezeErr) {
        console.error(`Failed to unfreeze for payment ${paymentId}:`, unfreezeErr.message);
      }
    }

    // Transition to FAILED (best effort)
    try {
      await updateStatus(null, paymentId, 'PROCESSING', 'FAILED');
    } catch {
      try {
        await updateStatus(null, paymentId, 'PENDING', 'REJECTED');
      } catch {
        console.error(`Could not update status for failed payment ${paymentId}`);
      }
    }

    // Publish failure event
    try {
      await publishEvent('payment.failed', from_account, {
        paymentId,
        from_account,
        to_account,
        amount,
        status: 'FAILED',
        error: err.message,
      });
    } catch {
      console.error(`Failed to publish failure event for payment ${paymentId}`);
    }
  } finally {
    if (lock) {
      try {
        await releaseLock(lock.lockKey, lock.lockValue);
      } catch (lockErr) {
        console.error(`Failed to release lock for payment ${paymentId}:`, lockErr.message);
      }
    }
  }
}

/**
 * Get a payment by its payment ID.
 */
async function getPaymentById(paymentId) {
  const result = await pool.query('SELECT * FROM payment_order WHERE payment_id = $1', [
    paymentId,
  ]);

  if (result.rows.length === 0) {
    throw new PaymentError(ERROR_TYPES.NOT_FOUND, `Payment ${paymentId} not found`);
  }

  return result.rows[0];
}

/**
 * List payments with pagination.
 */
async function listPayments(page, size) {
  const pageNum = Math.max(1, parseInt(page) || 1);
  const pageSize = Math.min(100, Math.max(1, parseInt(size) || 20));
  const offset = (pageNum - 1) * pageSize;

  const [dataResult, countResult] = await Promise.all([
    pool.query('SELECT * FROM payment_order ORDER BY created_at DESC LIMIT $1 OFFSET $2', [
      pageSize,
      offset,
    ]),
    pool.query('SELECT COUNT(*) AS total FROM payment_order'),
  ]);

  const total = parseInt(countResult.rows[0].total);

  return {
    data: dataResult.rows,
    page: pageNum,
    size: pageSize,
    total,
    totalPages: Math.ceil(total / pageSize),
  };
}

module.exports = {
  canTransitTo,
  createPayment,
  processPaymentAsync,
  getPaymentById,
  listPayments,
  updateStatus,
};
