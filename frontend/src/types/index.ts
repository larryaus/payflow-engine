export type PaymentStatus =
  | 'CREATED'
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'REJECTED'
  | 'REFUNDED';

export interface PaymentOrder {
  payment_id: string;
  from_account: string;
  to_account: string;
  amount: number; // 单位: 分
  currency: string;
  status: PaymentStatus;
  payment_method: string;
  memo?: string;
  created_at: string;
  completed_at?: string;
}

export interface CreatePaymentRequest {
  from_account: string;
  to_account: string;
  amount: number;
  currency: string;
  payment_method: string;
  memo?: string;
  callback_url?: string;
}

export interface RefundRequest {
  amount: number;
  reason: string;
}

export interface AccountBalance {
  account_id: string;
  available_balance: number;
  frozen_balance: number;
  currency: string;
  updated_at: string;
}
