import type { PaymentStatus } from '../types';

/**
 * 将分转换为元显示
 */
export function formatAmount(cents: number): string {
  if (typeof cents !== 'number' || isNaN(cents)) return '0.00';
  return (cents / 100).toFixed(2);
}

/**
 * 格式化时间
 */
export function formatTime(iso: string): string {
  if (!iso) return '-';
  const date = new Date(iso);
  if (isNaN(date.getTime())) return '-';
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/**
 * 支付状态配置
 */
export const statusConfig: Record<PaymentStatus, { label: string; color: string }> = {
  CREATED: { label: '已创建', color: 'default' },
  PENDING: { label: '待处理', color: 'blue' },
  PROCESSING: { label: '处理中', color: 'orange' },
  COMPLETED: { label: '已完成', color: 'green' },
  FAILED: { label: '失败', color: 'red' },
  REJECTED: { label: '已拒绝', color: 'red' },
  REFUNDED: { label: '已退款', color: 'purple' },
};
