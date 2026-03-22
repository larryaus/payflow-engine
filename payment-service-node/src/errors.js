const ERROR_TYPES = {
  NOT_FOUND: 'NOT_FOUND',
  IDEMPOTENCY_CONFLICT: 'IDEMPOTENCY_CONFLICT',
  RISK_REJECTED: 'RISK_REJECTED',
  INVALID_STATUS: 'INVALID_STATUS',
  INVALID_AMOUNT: 'INVALID_AMOUNT',
  ACCOUNT_BUSY: 'ACCOUNT_BUSY',
};

const STATUS_CODE_MAP = {
  [ERROR_TYPES.NOT_FOUND]: 404,
  [ERROR_TYPES.IDEMPOTENCY_CONFLICT]: 409,
  [ERROR_TYPES.RISK_REJECTED]: 400,
  [ERROR_TYPES.INVALID_STATUS]: 400,
  [ERROR_TYPES.INVALID_AMOUNT]: 400,
  [ERROR_TYPES.ACCOUNT_BUSY]: 429,
};

class PaymentError extends Error {
  constructor(type, message) {
    super(message || type);
    this.name = 'PaymentError';
    this.type = type;
  }
}

function errorHandler(err, req, res, _next) {
  if (err instanceof PaymentError) {
    const statusCode = STATUS_CODE_MAP[err.type] || 500;
    return res.status(statusCode).json({
      error: {
        code: err.type,
        message: err.message,
      },
    });
  }

  console.error('Unhandled error:', err);
  return res.status(500).json({
    error: {
      code: 'INTERNAL_ERROR',
      message: 'An unexpected error occurred',
    },
  });
}

module.exports = { PaymentError, ERROR_TYPES, errorHandler };
