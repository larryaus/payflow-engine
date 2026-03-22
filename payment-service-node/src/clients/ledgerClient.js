const axios = require('axios');
const config = require('../config');

const client = axios.create({
  baseURL: config.services.ledgerUrl,
  timeout: 10000,
});

/**
 * Create a double-entry ledger record.
 * @param {string} referenceId - Payment or refund ID
 * @param {string} debitAccount - Account to debit
 * @param {string} creditAccount - Account to credit
 * @param {number} amount - Amount in cents
 */
async function createEntry(referenceId, debitAccount, creditAccount, amount) {
  await client.post('/api/v1/ledger/entries', {
    reference_id: referenceId,
    debit_account: debitAccount,
    credit_account: creditAccount,
    amount,
  });
}

module.exports = { createEntry };
