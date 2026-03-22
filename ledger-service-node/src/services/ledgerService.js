const { v4: uuidv4 } = require('uuid');
const pool = require('../db');
const { LedgerError } = require('../errors');

function generateEntryId() {
  const uuid = uuidv4().replace(/-/g, '');
  return `LED_${uuid.substring(0, 12)}`;
}

async function createEntry({ reference_id, debit_account, credit_account, amount }) {
  if (!reference_id || !debit_account || !credit_account || amount == null) {
    throw new LedgerError(
      LedgerError.INVALID_ENTRY,
      'Missing required fields: reference_id, debit_account, credit_account, amount'
    );
  }

  if (typeof amount !== 'number' || amount <= 0) {
    throw new LedgerError(
      LedgerError.INVALID_ENTRY,
      'Amount must be a positive number'
    );
  }

  const entryId = generateEntryId();
  const entryType = 'PAYMENT';
  const status = 'COMPLETED';
  const currency = 'CNY';

  const query = `
    INSERT INTO ledger_entry (entry_id, payment_id, debit_account, credit_account, amount, currency, entry_type, status)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
    RETURNING id, entry_id, payment_id, debit_account, credit_account, amount, currency, entry_type, status, created_at
  `;

  const values = [entryId, reference_id, debit_account, credit_account, amount, currency, entryType, status];
  const result = await pool.query(query, values);

  return formatEntry(result.rows[0]);
}

async function getEntriesByPaymentId(paymentId) {
  if (!paymentId) {
    throw new LedgerError(
      LedgerError.INVALID_ENTRY,
      'payment_id query parameter is required'
    );
  }

  const query = `
    SELECT id, entry_id, payment_id, debit_account, credit_account, amount, currency, entry_type, status, created_at
    FROM ledger_entry
    WHERE payment_id = $1
    ORDER BY created_at ASC
  `;

  const result = await pool.query(query, [paymentId]);

  if (result.rows.length === 0) {
    throw new LedgerError(
      LedgerError.NOT_FOUND,
      `No ledger entries found for payment_id: ${paymentId}`
    );
  }

  return result.rows.map(formatEntry);
}

async function verifyBalance() {
  const query = `
    SELECT
      COALESCE(SUM(amount), 0) AS total_debit,
      COALESCE(SUM(amount), 0) AS total_credit
    FROM ledger_entry
    WHERE status = 'COMPLETED'
  `;

  const result = await pool.query(query);
  const row = result.rows[0];

  const totalDebit = parseInt(row.total_debit, 10);
  const totalCredit = parseInt(row.total_credit, 10);

  return {
    balanced: totalDebit === totalCredit,
    total_debit: totalDebit,
    total_credit: totalCredit,
  };
}

function formatEntry(row) {
  return {
    id: row.id,
    entry_id: row.entry_id,
    payment_id: row.payment_id,
    debit_account: row.debit_account,
    credit_account: row.credit_account,
    amount: parseInt(row.amount, 10),
    currency: row.currency,
    entry_type: row.entry_type,
    status: row.status,
    created_at: row.created_at,
  };
}

module.exports = {
  createEntry,
  getEntriesByPaymentId,
  verifyBalance,
};
