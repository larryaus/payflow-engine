const express = require("express");
const ruleEngine = require("../rules/ruleEngine");

const router = express.Router();

router.post("/check", (req, res) => {
  const { from_account, to_account, amount } = req.query;
  const parsedAmount = parseInt(amount, 10);
  const result = ruleEngine.evaluate(from_account, to_account, parsedAmount);
  res.json(result);
});

module.exports = router;
