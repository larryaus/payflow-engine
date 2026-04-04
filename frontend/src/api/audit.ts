import apiClient from './client';
import type { AuditLogEntry } from '../types';

/**
 * 查询审计日志列表（分页）
 */
export async function listAuditLogs(params?: {
  page?: number;
  size?: number;
}): Promise<{ data: AuditLogEntry[]; total: number }> {
  const response = await apiClient.get('/audit/logs', { params });
  return response.data;
}

/**
 * 按 traceId 查询审计轨迹
 */
export async function getAuditByTrace(traceId: string): Promise<AuditLogEntry[]> {
  const response = await apiClient.get(`/audit/trace/${traceId}`);
  return response.data;
}

/**
 * 按资源查询审计轨迹
 */
export async function getAuditByResource(
  resourceType: string,
  resourceId: string
): Promise<AuditLogEntry[]> {
  const response = await apiClient.get('/audit/resource', {
    params: { resource_type: resourceType, resource_id: resourceId },
  });
  return response.data;
}
