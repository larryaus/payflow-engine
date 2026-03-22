const axios = require('axios');

const MAX_RETRIES = 3;
const INITIAL_BACKOFF_MS = 2000;

/**
 * Send a webhook POST request with exponential backoff retry.
 * Retries up to 3 times with delays of 2s, 4s, 8s.
 * Fire-and-forget — returns void.
 *
 * @param {string} url - The callback URL to POST to.
 * @param {object} payload - The JSON payload to send.
 */
async function sendWebhookWithRetry(url, payload) {
  let backoff = INITIAL_BACKOFF_MS;

  for (let attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
    try {
      const response = await axios.post(url, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: 10000,
        validateStatus: () => true,
      });

      if (response.status < 300) {
        console.log(`[webhook] Sent successfully to ${url} (attempt ${attempt})`);
        return;
      }

      console.log(
        `[webhook] Non-success status ${response.status} from ${url} (attempt ${attempt})`
      );
    } catch (err) {
      console.log(
        `[webhook] Error sending to ${url} (attempt ${attempt}): ${err.message}`
      );
    }

    if (attempt <= MAX_RETRIES) {
      console.log(`[webhook] Retrying in ${backoff}ms...`);
      await sleep(backoff);
      backoff *= 2;
    }
  }

  console.log(`[webhook] Failed to deliver to ${url} after ${MAX_RETRIES} retries`);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = { sendWebhookWithRetry };
