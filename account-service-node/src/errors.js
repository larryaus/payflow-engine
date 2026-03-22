const AccountErrorType = {
  NOT_FOUND: 'NOT_FOUND',
  INSUFFICIENT_BALANCE: 'INSUFFICIENT_BALANCE',
  CONCURRENT_MODIFICATION: 'CONCURRENT_MODIFICATION',
  INVALID_AMOUNT: 'INVALID_AMOUNT',
};

const STATUS_CODE_MAP = {
  [AccountErrorType.NOT_FOUND]: 404,
  [AccountErrorType.INSUFFICIENT_BALANCE]: 400,
  [AccountErrorType.CONCURRENT_MODIFICATION]: 409,
  [AccountErrorType.INVALID_AMOUNT]: 400,
};

class AccountError extends Error {
  constructor(type, message) {
    super(message);
    this.name = 'AccountError';
    this.type = type;
    this.statusCode = STATUS_CODE_MAP[type] || 500;
  }
}

function errorHandler(err, req, res, next) {
  if (err instanceof AccountError) {
    return res.status(err.statusCode).json({
      error: err.type,
      message: err.message,
    });
  }

  console.error('Unhandled error:', err);
  res.status(500).json({
    error: 'INTERNAL_ERROR',
    message: 'An unexpected error occurred',
  });
}

module.exports = { AccountError, AccountErrorType, errorHandler };
