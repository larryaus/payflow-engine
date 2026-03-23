import apiClient from './client';
import type { PaymentOrder, CreatePaymentRequest, RefundRequest, RefundOrder } from '../types';

/**
 * 生成幂等键
 */
function generateIdempotencyKey(): string {
  return crypto.randomUUID();
}

/**
 * 创建支付订单
 */
export async function createPayment(data: CreatePaymentRequest): Promise<PaymentOrder> {
  const response = await apiClient.post('/payments', data, {
    headers: { 'Idempotency-Key': generateIdempotencyKey() },
  });
  return response.data;
}

/**
 * 查询支付详情
 */
export async function getPayment(paymentId: string): Promise<PaymentOrder> {
  const response = await apiClient.get(`/payments/${paymentId}`);
  return response.data;
}

/**
 * 查询支付列表 (分页)
 */
export async function listPayments(params?: {
  page?: number;
  size?: number;
  status?: string;
}): Promise<{ data: PaymentOrder[]; total: number }> {
  const response = await apiClient.get('/payments', { params });
  return response.data;
}

/**
 * 发起退款
 */
export async function refundPayment(paymentId: string, data: RefundRequest): Promise<RefundOrder> {
  const response = await apiClient.post(`/payments/${paymentId}/refund`, data, {
    headers: { 'Idempotency-Key': generateIdempotencyKey() },
  });
  return response.data;
}

/**
 * 查询退款列表
 */
export async function listRefunds(paymentId: string): Promise<RefundOrder[]> {
  const response = await apiClient.get(`/payments/${paymentId}/refunds`);
  return response.data;
}
