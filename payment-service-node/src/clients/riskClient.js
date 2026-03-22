const axios = require('axios');
const config = require('../config');

const client = axios.create({
  baseURL: config.services.riskUrl,
  timeout: 5000,
});

/**
 * Check transaction risk via the risk-service.
 * @param {string} fromAccount - Source account ID
 * @param {string} toAccount - Destination account ID
 * @param {number} amount - Amount in cents
 * @returns {Promise<boolean>} true if approved, false if rejected
 */
async function checkRisk(fromAccount, toAccount, amount) {
  try {
    const response = await client.post('/api/v1/risk/check', null, {
      params: {
        from_account: fromAccount,
        to_account: toAccount,
        amount,
      },
    });
    return response.data === true;
  } catch (err) {
    if (err.response && err.response.status === 400) {
      return false;
    }
    throw err;
  }
}

module.exports = { checkRisk };
