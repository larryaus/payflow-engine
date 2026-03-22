const express = require('express');
const { sendWebhookWithRetry } = require('../handlers/webhook');

const router = express.Router();

/**
 * POST /api/v1/notify/webhook
 * Manual webhook trigger.
 * Body: { callback_url: "https://...", ...payload }
 * Returns 202 Accepted and fires the webhook asynchronously.
 */
router.post('/webhook', (req, res) => {
  const payload = req.body;

  if (!payload || !payload.callback_url) {
    return res.status(400).json({ error: 'missing callback_url' });
  }

  const { callback_url: callbackUrl } = payload;

  // Fire and forget — do not await
  sendWebhookWithRetry(callbackUrl, payload).catch((err) => {
    console.error(`[notify] Unhandled error in webhook delivery: ${err.message}`);
  });

  return res.status(202).json({ status: 'accepted' });
});

module.exports = router;
