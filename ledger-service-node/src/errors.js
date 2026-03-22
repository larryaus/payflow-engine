class LedgerError extends Error {
  constructor(type, message) {
    super(message);
    this.name = 'LedgerError';
    this.type = type;
  }

  static NOT_FOUND = 'NOT_FOUND';
  static INVALID_ENTRY = 'INVALID_ENTRY';

  get statusCode() {
    switch (this.type) {
      case LedgerError.NOT_FOUND:
        return 404;
      case LedgerError.INVALID_ENTRY:
        return 400;
      default:
        return 500;
    }
  }
}

module.exports = { LedgerError };
