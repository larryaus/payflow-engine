const express = require('express');
const router = express.Router();
const accountService = require('../services/accountService');

router.get('/:accountId/balance', async (req, res, next) => {
  try {
    const balance = await accountService.getBalance(req.params.accountId);
    res.json(balance);
  } catch (err) {
    next(err);
  }
});

router.post('/freeze', async (req, res, next) => {
  try {
    const { account_id, amount } = req.body;
    const result = await accountService.freeze(account_id, amount);
    res.json(result);
  } catch (err) {
    next(err);
  }
});

router.post('/unfreeze', async (req, res, next) => {
  try {
    const { account_id, amount } = req.body;
    const result = await accountService.unfreeze(account_id, amount);
    res.json(result);
  } catch (err) {
    next(err);
  }
});

router.post('/transfer', async (req, res, next) => {
  try {
    const { from_account, to_account, amount } = req.body;
    const result = await accountService.transfer(from_account, to_account, amount);
    res.json(result);
  } catch (err) {
    next(err);
  }
});

module.exports = router;
