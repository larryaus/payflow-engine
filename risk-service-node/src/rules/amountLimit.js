const SINGLE_TRANSACTION_LIMIT = 5000000;

function check(from_account, to_account, amount) {
  if (amount <= 0 || amount > SINGLE_TRANSACTION_LIMIT) {
    return false;
  }
  return true;
}

module.exports = { check };
