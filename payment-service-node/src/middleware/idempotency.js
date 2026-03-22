const redis = require('../redis');
const { PaymentError, ERROR_TYPES } = require('../errors');

const IDEMPOTENCY_TTL = 86400; // 24 hours in seconds
const POLL_INTERVAL_MS = 200;
const MAX_POLL_ATTEMPTS = 5;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function idempotency() {
  return async (req, res, next) => {
    if (req.method !== 'POST') {
      return next();
    }

    const idempotencyKey =
      req.headers['idempotency-key'] || req.headers['Idempotency-Key'];

    if (!idempotencyKey) {
      return res.status(400).json({
        error: {
          code: 'MISSING_IDEMPOTENCY_KEY',
          message: 'Idempotency-Key header is required for POST requests',
        },
      });
    }

    const redisKey = `idempotent:${idempotencyKey}`;

    // Try to acquire the idempotency slot
    const acquired = await redis.set(
      redisKey,
      'PROCESSING',
      'EX',
      IDEMPOTENCY_TTL,
      'NX'
    );

    if (acquired) {
      // First request with this key — intercept the response to cache it
      const originalJson = res.json.bind(res);

      res.json = async (body) => {
        const cachedResult = JSON.stringify({
          statusCode: res.statusCode,
          body,
        });
        await redis.set(redisKey, cachedResult, 'EX', IDEMPOTENCY_TTL);
        return originalJson(body);
      };

      // Attach the key for downstream use
      req.idempotencyKey = idempotencyKey;
      return next();
    }

    // Duplicate request — poll for the result
    for (let attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
      await sleep(POLL_INTERVAL_MS);

      const stored = await redis.get(redisKey);

      if (!stored) {
        // Key expired between checks — allow retry
        req.idempotencyKey = idempotencyKey;
        return next();
      }

      if (stored !== 'PROCESSING') {
        try {
          const cached = JSON.parse(stored);
          return res.status(cached.statusCode).json(cached.body);
        } catch {
          // Corrupted data — allow retry
          await redis.del(redisKey);
          req.idempotencyKey = idempotencyKey;
          return next();
        }
      }
    }

    // Still processing after all poll attempts
    throw new PaymentError(
      ERROR_TYPES.IDEMPOTENCY_CONFLICT,
      'Request with this Idempotency-Key is still being processed'
    );
  };
}

module.exports = idempotency;
