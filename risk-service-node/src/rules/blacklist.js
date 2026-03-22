const BLACKLIST = new Set();

function check(from_account, to_account, amount) {
  if (BLACKLIST.has(from_account) || BLACKLIST.has(to_account)) {
    return false;
  }
  return true;
}

module.exports = { check, BLACKLIST };
