const blacklist = require("./blacklist");
const amountLimit = require("./amountLimit");

const checkers = [blacklist, amountLimit];

function evaluate(from_account, to_account, amount) {
  for (const checker of checkers) {
    if (!checker.check(from_account, to_account, amount)) {
      return false;
    }
  }
  return true;
}

module.exports = { checkers, evaluate };
