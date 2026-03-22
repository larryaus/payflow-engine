const axios = require('axios');
const config = require('../config');

const client = axios.create({
  baseURL: config.services.accountUrl,
  timeout: 10000,
});

/**
 * Freeze (hold) an amount on an account.
 * @param {string} accountId - Account ID
 * @param {number} amount - Amount in cents to freeze
 */
async function freeze(accountId, amount) {
  await client.post('/api/v1/accounts/freeze', {
    account_id: accountId,
    amount,
  });
}

/**
 * Release a previously frozen amount.
 * @param {string} accountId - Account ID
 * @param {number} amount - Amount in cents to unfreeze
 */
async function unfreeze(accountId, amount) {
  await client.post('/api/v1/accounts/unfreeze', {
    account_id: accountId,
    amount,
  });
}

/**
 * Execute a transfer between two accounts.
 * @param {string} fromAccount - Source account ID
 * @param {string} toAccount - Destination account ID
 * @param {number} amount - Amount in cents
 */
async function transfer(fromAccount, toAccount, amount) {
  await client.post('/api/v1/accounts/transfer', {
    from_account: fromAccount,
    to_account: toAccount,
    amount,
  });
}

module.exports = { freeze, unfreeze, transfer };
