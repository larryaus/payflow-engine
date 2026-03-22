import apiClient from './client';
import type { AccountBalance } from '../types';

/**
 * 查询所有账户
 */
export async function listAccounts(): Promise<AccountBalance[]> {
  const response = await apiClient.get('/accounts');
  return response.data;
}

/**
 * 查询账户余额
 */
export async function getAccountBalance(accountId: string): Promise<AccountBalance> {
  const response = await apiClient.get(`/accounts/${accountId}/balance`);
  return response.data;
}
