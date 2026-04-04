import { useState, useEffect } from 'react';
import { Table, Tag, Button, Space, Input, Select, message } from 'antd';
import { SearchOutlined, PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { listPayments } from '../api/payment';
import type { PaymentOrder, PaymentStatus } from '../types';
import { formatAmount, formatTime, statusConfig } from '../utils/format';

function PaymentListPage() {
  const navigate = useNavigate();
  const [data, setData] = useState<PaymentOrder[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string>();
  const [searchText, setSearchText] = useState('');

  const fetchData = async () => {
    setLoading(true);
    try {
      const result = await listPayments({ page, size: 20, status: statusFilter });
      setData(result.data);
      setTotal(result.total);
    } catch {
      message.error('加载支付列表失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [page, statusFilter]);

  const columns = [
    {
      title: '支付单号',
      dataIndex: 'payment_id',
      key: 'payment_id',
      render: (id: string) => (
        <a onClick={() => navigate(`/payments/${id}`)}>{id}</a>
      ),
    },
    {
      title: '付款账户',
      dataIndex: 'from_account',
      key: 'from_account',
    },
    {
      title: '收款账户',
      dataIndex: 'to_account',
      key: 'to_account',
    },
    {
      title: '金额',
      dataIndex: 'amount',
      key: 'amount',
      render: (amount: number, record: PaymentOrder) =>
        `${formatAmount(amount)} ${record.currency}`,
      align: 'right' as const,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: PaymentStatus) => {
        const config = statusConfig[status];
        return <Tag color={config.color}>{config.label}</Tag>;
      },
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      key: 'created_at',
      render: (time: string) => formatTime(time),
    },
    {
      title: '操作',
      key: 'action',
      render: (_: unknown, record: PaymentOrder) => (
        <Button type="link" onClick={() => navigate(`/payments/${record.payment_id}`)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Space>
          <Select
            placeholder="筛选状态"
            allowClear
            style={{ width: 150 }}
            onChange={(v) => setStatusFilter(v)}
            options={[
              { value: 'PENDING', label: '待处理' },
              { value: 'PROCESSING', label: '处理中' },
              { value: 'COMPLETED', label: '已完成' },
              { value: 'FAILED', label: '失败' },
              { value: 'REFUNDED', label: '已退款' },
            ]}
          />
          <Input
            placeholder="搜索支付单号"
            prefix={<SearchOutlined />}
            style={{ width: 250 }}
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            allowClear
          />
        </Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/payments/create')}>
          发起支付
        </Button>
      </div>
      <Table
        columns={columns}
        dataSource={searchText ? data.filter(d => d.payment_id.toLowerCase().includes(searchText.toLowerCase())) : data}
        rowKey="payment_id"
        loading={loading}
        pagination={{
          current: page,
          total,
          pageSize: 20,
          onChange: setPage,
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </div>
  );
}

export default PaymentListPage;
