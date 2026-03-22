/**
 * Mock backend server for PayFlow Engine
 * Simulates payment-service (8081) and account-service (8082)
 * with realistic seed data so the frontend can load without errors.
 */

const http = require('http');
const crypto = require('crypto');

// ─── Seed Data ───────────────────────────────────────────────────────────────

const accounts = {
  'ACC_001': { account_id: 'ACC_001', account_name: '张三', available_balance: 500000, frozen_balance: 0, currency: 'CNY', updated_at: new Date().toISOString() },
  'ACC_002': { account_id: 'ACC_002', account_name: '李四', available_balance: 320000, frozen_balance: 10000, currency: 'CNY', updated_at: new Date().toISOString() },
  'ACC_003': { account_id: 'ACC_003', account_name: '王五', available_balance: 1000000, frozen_balance: 0, currency: 'CNY', updated_at: new Date().toISOString() },
};

const payments = [
  { payment_id: 'PAY_20260322_a1b2c3d4', from_account: 'ACC_001', to_account: 'ACC_002', amount: 50000, currency: 'CNY', status: 'COMPLETED', payment_method: 'BANK_TRANSFER', memo: '货款结算', created_at: '2026-03-22T08:30:00Z' },
  { payment_id: 'PAY_20260322_e5f6g7h8', from_account: 'ACC_002', to_account: 'ACC_003', amount: 12000, currency: 'CNY', status: 'PENDING', payment_method: 'BANK_TRANSFER', memo: '服务费', created_at: '2026-03-22T09:15:00Z' },
  { payment_id: 'PAY_20260321_i9j0k1l2', from_account: 'ACC_003', to_account: 'ACC_001', amount: 200000, currency: 'CNY', status: 'COMPLETED', payment_method: 'BANK_TRANSFER', memo: '项目款', created_at: '2026-03-21T14:00:00Z' },
  { payment_id: 'PAY_20260321_m3n4o5p6', from_account: 'ACC_001', to_account: 'ACC_003', amount: 8000, currency: 'CNY', status: 'FAILED', payment_method: 'BANK_TRANSFER', memo: '退款', created_at: '2026-03-21T16:45:00Z' },
  { payment_id: 'PAY_20260320_q7r8s9t0', from_account: 'ACC_002', to_account: 'ACC_001', amount: 35000, currency: 'CNY', status: 'COMPLETED', payment_method: 'BANK_TRANSFER', memo: '订单付款', created_at: '2026-03-20T11:20:00Z' },
];

let nextPayments = [...payments];

// ─── Helper ───────────────────────────────────────────────────────────────────

function sendJson(res, statusCode, data) {
  const body = JSON.stringify(data);
  res.writeHead(statusCode, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', chunk => data += chunk);
    req.on('end', () => {
      try { resolve(JSON.parse(data)); } catch { resolve({}); }
    });
  });
}

// ─── Router ───────────────────────────────────────────────────────────────────

async function handleRequest(req, res) {
  const url = new URL(req.url, `http://localhost`);
  const path = url.pathname;
  const method = req.method;

  if (method === 'OPTIONS') {
    res.writeHead(200, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': '*' });
    return res.end();
  }

  // ── Payment Service (8081) ──────────────────────────────────────────────────

  // GET /api/v1/payments — list payments (paginated)
  if (method === 'GET' && path === '/api/v1/payments') {
    const page = parseInt(url.searchParams.get('page') || '1');
    const size = parseInt(url.searchParams.get('size') || '20');
    const status = url.searchParams.get('status');
    let data = [...nextPayments].sort((a, b) => b.created_at.localeCompare(a.created_at));
    if (status) data = data.filter(p => p.status === status);
    const total = data.length;
    const start = (page - 1) * size;
    return sendJson(res, 200, { data: data.slice(start, start + size), total });
  }

  // GET /api/v1/payments/:id
  if (method === 'GET' && path.startsWith('/api/v1/payments/')) {
    const id = path.split('/')[4];
    const p = nextPayments.find(x => x.payment_id === id);
    if (!p) return sendJson(res, 404, { message: 'Not found' });
    return sendJson(res, 200, p);
  }

  // POST /api/v1/payments — create payment
  if (method === 'POST' && path === '/api/v1/payments') {
    const body = await readBody(req);
    const id = `PAY_${new Date().toISOString().slice(0,10).replace(/-/g,'')}_${crypto.randomBytes(4).toString('hex')}`;
    const payment = {
      payment_id: id,
      from_account: body.from_account || 'ACC_001',
      to_account: body.to_account || 'ACC_002',
      amount: body.amount || 0,
      currency: body.currency || 'CNY',
      status: 'PENDING',
      payment_method: body.payment_method || 'BANK_TRANSFER',
      memo: body.memo || '',
      created_at: new Date().toISOString(),
    };
    nextPayments.unshift(payment);
    // Simulate async completion
    setTimeout(() => {
      const p = nextPayments.find(x => x.payment_id === id);
      if (p) p.status = 'COMPLETED';
    }, 3000);
    return sendJson(res, 201, payment);
  }

  // POST /api/v1/payments/:id/refund
  if (method === 'POST' && path.match(/\/api\/v1\/payments\/[^/]+\/refund/)) {
    const id = path.split('/')[4];
    const p = nextPayments.find(x => x.payment_id === id);
    if (!p) return sendJson(res, 404, { message: 'Not found' });
    const refundId = `REF_${Date.now()}`;
    return sendJson(res, 201, { refund_id: refundId, payment_id: id, status: 'REFUNDING', amount: p.amount });
  }

  // ── Account Service (8082) ──────────────────────────────────────────────────

  // GET /api/v1/accounts/:id/balance
  if (method === 'GET' && path.match(/\/api\/v1\/accounts\/[^/]+\/balance/)) {
    const accountId = path.split('/')[4];
    const account = accounts[accountId];
    if (!account) return sendJson(res, 404, { message: `Account not found: ${accountId}` });
    return sendJson(res, 200, account);
  }

  // POST /api/v1/accounts/freeze
  if (method === 'POST' && path === '/api/v1/accounts/freeze') {
    return sendJson(res, 200, {});
  }

  // POST /api/v1/accounts/unfreeze
  if (method === 'POST' && path === '/api/v1/accounts/unfreeze') {
    return sendJson(res, 200, {});
  }

  // POST /api/v1/accounts/transfer
  if (method === 'POST' && path === '/api/v1/accounts/transfer') {
    return sendJson(res, 200, {});
  }

  // ── Risk Service (8084) ─────────────────────────────────────────────────────
  if (method === 'POST' && path === '/api/v1/risk/check') {
    return sendJson(res, 200, true);
  }

  return sendJson(res, 404, { message: 'Not found' });
}

// ─── Start servers ────────────────────────────────────────────────────────────

// Payment service on 8081
http.createServer(handleRequest).listen(8081, () => {
  console.log('Payment service mock running on :8081');
});

// Account service on 8082
http.createServer(handleRequest).listen(8082, () => {
  console.log('Account service mock running on :8082');
});

console.log('Mock backend started. Serving payment + account APIs.');
