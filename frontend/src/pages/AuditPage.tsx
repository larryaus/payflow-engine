import { useState, useEffect } from 'react';
import { Table, Tag, Input, Space, Modal, Timeline, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { listAuditLogs, getAuditByTrace } from '../api/audit';
import type { AuditLogEntry } from '../types';
import { formatTime } from '../utils/format';

const resultColor: Record<string, string> = {
  SUCCESS: 'green',
  FAILED: 'red',
  INFO: 'blue',
  COMPLETED: 'green',
  PROCESSING: 'orange',
};

function AuditPage() {
  const [data, setData] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [traceModalOpen, setTraceModalOpen] = useState(false);
  const [traceData, setTraceData] = useState<AuditLogEntry[]>([]);
  const [currentTraceId, setCurrentTraceId] = useState('');

  const fetchData = async () => {
    setLoading(true);
    try {
      const result = await listAuditLogs({ page, size: 20 });
      setData(result.data);
      setTotal(result.total);
    } catch {
      message.error('加载审计日志失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [page]);

  const showTrace = async (traceId: string) => {
    if (!traceId) return;
    setCurrentTraceId(traceId);
    try {
      const result = await getAuditByTrace(traceId);
      setTraceData(result);
      setTraceModalOpen(true);
    } catch {
      message.error('加载链路追踪失败');
    }
  };

  const columns = [
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (time: string) => formatTime(time),
    },
    {
      title: 'Trace ID',
      dataIndex: 'traceId',
      key: 'traceId',
      width: 160,
      ellipsis: true,
      render: (traceId: string) => (
        <a onClick={() => showTrace(traceId)} title={traceId}>
          {traceId ? traceId.substring(0, 12) + '...' : '-'}
        </a>
      ),
    },
    {
      title: '操作',
      dataIndex: 'action',
      key: 'action',
      width: 180,
    },
    {
      title: '资源类型',
      dataIndex: 'resourceType',
      key: 'resourceType',
      width: 100,
    },
    {
      title: '资源 ID',
      dataIndex: 'resourceId',
      key: 'resourceId',
      width: 200,
      ellipsis: true,
    },
    {
      title: '详情',
      dataIndex: 'detail',
      key: 'detail',
      ellipsis: true,
    },
    {
      title: '结果',
      dataIndex: 'result',
      key: 'result',
      width: 100,
      render: (result: string) => (
        <Tag color={resultColor[result] || 'default'}>{result}</Tag>
      ),
    },
    {
      title: '来源 IP',
      dataIndex: 'clientIp',
      key: 'clientIp',
      width: 130,
      render: (ip: string) => ip || '-',
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Space>
          <Input
            placeholder="搜索 Trace ID"
            prefix={<SearchOutlined />}
            style={{ width: 300 }}
            onPressEnter={(e) => showTrace((e.target as HTMLInputElement).value)}
          />
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={data}
        rowKey="id"
        loading={loading}
        pagination={{
          current: page,
          total,
          pageSize: 20,
          onChange: setPage,
          showTotal: (t) => `共 ${t} 条`,
        }}
        size="small"
      />

      <Modal
        title={`链路追踪: ${currentTraceId}`}
        open={traceModalOpen}
        onCancel={() => setTraceModalOpen(false)}
        footer={null}
        width={700}
      >
        <Timeline
          items={traceData.map((item) => ({
            color: item.result === 'SUCCESS' || item.result === 'COMPLETED' ? 'green' :
                   item.result === 'FAILED' ? 'red' : 'blue',
            children: (
              <div>
                <div style={{ fontWeight: 500 }}>
                  {item.action}
                  <Tag color={resultColor[item.result] || 'default'} style={{ marginLeft: 8 }}>
                    {item.result}
                  </Tag>
                </div>
                <div style={{ color: '#888', fontSize: 12 }}>
                  {formatTime(item.createdAt)} | {item.resourceType}:{item.resourceId}
                </div>
                {item.detail && (
                  <div style={{ color: '#666', fontSize: 12, marginTop: 4 }}>{item.detail}</div>
                )}
              </div>
            ),
          }))}
        />
      </Modal>
    </div>
  );
}

export default AuditPage;
