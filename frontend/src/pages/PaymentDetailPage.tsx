import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Descriptions, Tag, Card, Button, Modal, InputNumber, Input, message, Spin, Steps, Table } from 'antd';
import { getPayment, refundPayment, listRefunds } from '../api/payment';
import type { PaymentOrder, PaymentStatus, RefundOrder } from '../types';
import { formatAmount, formatTime, statusConfig } from '../utils/format';

const statusSteps: PaymentStatus[] = ['CREATED', 'PENDING', 'PROCESSING', 'COMPLETED'];

function PaymentDetailPage() {
  const { paymentId } = useParams<{ paymentId: string }>();
  const navigate = useNavigate();
  const [order, setOrder] = useState<PaymentOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [refunds, setRefunds] = useState<RefundOrder[]>([]);
  const [refundVisible, setRefundVisible] = useState(false);
  const [refundAmount, setRefundAmount] = useState<number>(0);
  const [refundReason, setRefundReason] = useState('');

  const fetchOrder = async () => {
    if (!paymentId) return;
    setLoading(true);
    try {
      const [data, refundData] = await Promise.all([
        getPayment(paymentId),
        listRefunds(paymentId),
      ]);
      setOrder(data);
      setRefunds(refundData);
    } catch {
      message.error('加载支付详情失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchOrder();
  }, [paymentId]);

  const handleRefund = async () => {
    if (!paymentId) return;
    try {
      await refundPayment(paymentId, {
        amount: Math.round(refundAmount * 100),
        reason: refundReason,
      });
      message.success('退款发起成功');
      setRefundVisible(false);
      fetchOrder();
    } catch {
      message.error('退款失败');
    }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!order) return <div>支付单不存在</div>;

  const currentStep = statusSteps.indexOf(order.status);
  const isFailed = order.status === 'FAILED' || order.status === 'REJECTED';

  return (
    <div>
      <Button onClick={() => navigate('/payments')} style={{ marginBottom: 16 }}>
        返回列表
      </Button>

      <Card title="支付进度" style={{ marginBottom: 24 }}>
        <Steps
          current={isFailed ? statusSteps.indexOf('PROCESSING') : currentStep}
          status={isFailed ? 'error' : undefined}
          items={statusSteps.map((s) => ({
            title: statusConfig[s].label,
          }))}
        />
      </Card>

      <Card
        title="支付详情"
        extra={
          order.status === 'COMPLETED' && (
            <Button danger onClick={() => setRefundVisible(true)}>
              发起退款
            </Button>
          )
        }
      >
        <Descriptions column={2} bordered>
          <Descriptions.Item label="支付单号">{order.payment_id}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={statusConfig[order.status].color}>
              {statusConfig[order.status].label}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="付款账户">{order.from_account}</Descriptions.Item>
          <Descriptions.Item label="收款账户">{order.to_account}</Descriptions.Item>
          <Descriptions.Item label="金额">
            {formatAmount(order.amount)} {order.currency}
          </Descriptions.Item>
          <Descriptions.Item label="支付方式">{order.payment_method}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{formatTime(order.created_at)}</Descriptions.Item>
          <Descriptions.Item label="完成时间">
            {order.completed_at ? formatTime(order.completed_at) : '-'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {refunds.length > 0 && (
        <Card title="退款记录" style={{ marginTop: 24 }}>
          <Table
            rowKey="refund_id"
            dataSource={refunds}
            pagination={false}
            columns={[
              { title: '退款单号', dataIndex: 'refund_id' },
              {
                title: '金额',
                dataIndex: 'amount',
                render: (v: number) => `${formatAmount(v)} CNY`,
              },
              {
                title: '状态',
                dataIndex: 'status',
                render: (s: string) => {
                  const color = s === 'COMPLETED' ? 'green' : s === 'FAILED' ? 'red' : 'blue';
                  return <Tag color={color}>{s}</Tag>;
                },
              },
              { title: '原因', dataIndex: 'reason' },
              {
                title: '创建时间',
                dataIndex: 'created_at',
                render: (v: string) => formatTime(v),
              },
              {
                title: '完成时间',
                dataIndex: 'completed_at',
                render: (v?: string) => (v ? formatTime(v) : '-'),
              },
            ]}
          />
        </Card>
      )}

      <Modal
        title="发起退款"
        open={refundVisible}
        onOk={handleRefund}
        onCancel={() => setRefundVisible(false)}
        okText="确认退款"
        cancelText="取消"
      >
        <div style={{ marginBottom: 16 }}>
          <label>退款金额 (元，最多 {formatAmount(order.amount)})</label>
          <InputNumber
            style={{ width: '100%', marginTop: 8 }}
            min={0.01}
            max={order.amount / 100}
            precision={2}
            value={refundAmount}
            onChange={(v) => setRefundAmount(v || 0)}
          />
        </div>
        <div>
          <label>退款原因</label>
          <Input.TextArea
            rows={3}
            style={{ marginTop: 8 }}
            value={refundReason}
            onChange={(e) => setRefundReason(e.target.value)}
            placeholder="请输入退款原因"
          />
        </div>
      </Modal>
    </div>
  );
}

export default PaymentDetailPage;
