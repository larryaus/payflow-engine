const pool = require('../db');
const { AccountError, AccountErrorType } = require('../errors');

async function getBalance(accountId) {
  const result = await pool.query(
    'SELECT account_id, available_balance, frozen_balance, currency, updated_at FROM account WHERE account_id = $1',
    [accountId]
  );

  if (result.rows.length === 0) {
    throw new AccountError(AccountErrorType.NOT_FOUND, `Account ${accountId} not found`);
  }

  const row = result.rows[0];
  return {
    account_id: row.account_id,
    available_balance: parseInt(row.available_balance),
    frozen_balance: parseInt(row.frozen_balance),
    currency: row.currency,
    updated_at: row.updated_at,
  };
}

async function freeze(accountId, amount) {
  amount = parseInt(amount);

  if (!amount || amount <= 0) {
    throw new AccountError(AccountErrorType.INVALID_AMOUNT, 'Amount must be a positive integer');
  }

  const selectResult = await pool.query(
    'SELECT available_balance, frozen_balance, version FROM account WHERE account_id = $1',
    [accountId]
  );

  if (selectResult.rows.length === 0) {
    throw new AccountError(AccountErrorType.NOT_FOUND, `Account ${accountId} not found`);
  }

  const account = selectResult.rows[0];
  const availableBalance = parseInt(account.available_balance);
  const frozenBalance = parseInt(account.frozen_balance);
  const version = parseInt(account.version);

  if (availableBalance < amount) {
    throw new AccountError(
      AccountErrorType.INSUFFICIENT_BALANCE,
      `Insufficient available balance. Available: ${availableBalance}, requested: ${amount}`
    );
  }

  const newAvailable = availableBalance - amount;
  const newFrozen = frozenBalance + amount;

  const updateResult = await pool.query(
    `UPDATE account SET available_balance = $1, frozen_balance = $2, version = version + 1, updated_at = NOW()
     WHERE account_id = $3 AND version = $4`,
    [newAvailable, newFrozen, accountId, version]
  );

  if (updateResult.rowCount === 0) {
    throw new AccountError(
      AccountErrorType.CONCURRENT_MODIFICATION,
      'Account was modified by another transaction, please retry'
    );
  }

  return { success: true };
}

async function unfreeze(accountId, amount) {
  amount = parseInt(amount);

  if (!amount || amount <= 0) {
    throw new AccountError(AccountErrorType.INVALID_AMOUNT, 'Amount must be a positive integer');
  }

  const selectResult = await pool.query(
    'SELECT available_balance, frozen_balance, version FROM account WHERE account_id = $1',
    [accountId]
  );

  if (selectResult.rows.length === 0) {
    throw new AccountError(AccountErrorType.NOT_FOUND, `Account ${accountId} not found`);
  }

  const account = selectResult.rows[0];
  const availableBalance = parseInt(account.available_balance);
  const frozenBalance = parseInt(account.frozen_balance);
  const version = parseInt(account.version);

  if (frozenBalance < amount) {
    throw new AccountError(
      AccountErrorType.INSUFFICIENT_BALANCE,
      `Insufficient frozen balance. Frozen: ${frozenBalance}, requested: ${amount}`
    );
  }

  const newFrozen = frozenBalance - amount;
  const newAvailable = availableBalance + amount;

  const updateResult = await pool.query(
    `UPDATE account SET available_balance = $1, frozen_balance = $2, version = version + 1, updated_at = NOW()
     WHERE account_id = $3 AND version = $4`,
    [newAvailable, newFrozen, accountId, version]
  );

  if (updateResult.rowCount === 0) {
    throw new AccountError(
      AccountErrorType.CONCURRENT_MODIFICATION,
      'Account was modified by another transaction, please retry'
    );
  }

  return { success: true };
}

async function transfer(fromAccount, toAccount, amount) {
  amount = parseInt(amount);

  if (!amount || amount <= 0) {
    throw new AccountError(AccountErrorType.INVALID_AMOUNT, 'Amount must be a positive integer');
  }

  const client = await pool.connect();

  try {
    await client.query('BEGIN');

    // Pessimistic lock: lock both rows in a deterministic order to avoid deadlocks
    const accountIds = [fromAccount, toAccount].sort();
    const lockResult = await client.query(
      'SELECT * FROM account WHERE account_id = ANY($1) FOR UPDATE',
      [accountIds]
    );

    const accountMap = {};
    for (const row of lockResult.rows) {
      accountMap[row.account_id] = row;
    }

    const sender = accountMap[fromAccount];
    const receiver = accountMap[toAccount];

    if (!sender) {
      throw new AccountError(AccountErrorType.NOT_FOUND, `Account ${fromAccount} not found`);
    }
    if (!receiver) {
      throw new AccountError(AccountErrorType.NOT_FOUND, `Account ${toAccount} not found`);
    }

    const senderFrozen = parseInt(sender.frozen_balance);

    if (senderFrozen < amount) {
      throw new AccountError(
        AccountErrorType.INSUFFICIENT_BALANCE,
        `Insufficient frozen balance. Frozen: ${senderFrozen}, requested: ${amount}`
      );
    }

    // Debit from sender's frozen balance
    await client.query(
      `UPDATE account SET frozen_balance = frozen_balance - $1, version = version + 1, updated_at = NOW()
       WHERE account_id = $2`,
      [amount, fromAccount]
    );

    // Credit to receiver's available balance
    await client.query(
      `UPDATE account SET available_balance = available_balance + $1, version = version + 1, updated_at = NOW()
       WHERE account_id = $2`,
      [amount, toAccount]
    );

    await client.query('COMMIT');

    return { success: true };
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}

module.exports = { getBalance, freeze, unfreeze, transfer };
