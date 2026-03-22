const express = require('express');
const router = express.Router();
const ledgerService = require('../services/ledgerService');
const { LedgerError } = require('../errors');

router.post('/entries', async (req, res, next) => {
  try {
    const entry = await ledgerService.createEntry(req.body);
    res.status(201).json(entry);
  } catch (err) {
    next(err);
  }
});

router.get('/entries', async (req, res, next) => {
  try {
    const { payment_id } = req.query;
    const entries = await ledgerService.getEntriesByPaymentId(payment_id);
    res.json(entries);
  } catch (err) {
    next(err);
  }
});

router.get('/verify', async (req, res, next) => {
  try {
    const result = await ledgerService.verifyBalance();
    res.json(result);
  } catch (err) {
    next(err);
  }
});

module.exports = router;
