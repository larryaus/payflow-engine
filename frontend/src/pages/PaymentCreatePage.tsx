import { Form, Input, InputNumber, Select, Button, Card, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { createPayment } from '../api/payment';
import type { CreatePaymentRequest } from '../types';

function PaymentCreatePage() {
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const onFinish = async (values: CreatePaymentRequest) => {
    try {
      // 将元转换为分
      const request = { ...values, amount: Math.round(values.amount * 100) };
      const result = await createPayment(request);
      message.success(`支付单创建成功: ${result.payment_id}`);
      navigate(`/payments/${result.payment_id}`);
    } catch {
      message.error('创建支付失败');
    }
  };

  return (
    <Card title="发起支付" style={{ maxWidth: 640, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={onFinish}>
        <Form.Item
          name="from_account"
          label="付款账户"
          rules={[{ required: true, message: '请输入付款账户' }]}
        >
          <Input placeholder="例: ACC_001" />
        </Form.Item>

        <Form.Item
          name="to_account"
          label="收款账户"
          rules={[{ required: true, message: '请输入收款账户' }]}
        >
          <Input placeholder="例: ACC_002" />
        </Form.Item>

        <Form.Item
          name="amount"
          label="金额 (元)"
          rules={[
            { required: true, message: '请输入金额' },
            { type: 'number', min: 0.01, message: '金额必须大于 0' },
          ]}
        >
          <InputNumber
            style={{ width: '100%' }}
            precision={2}
            placeholder="0.00"
            addonAfter="CNY"
          />
        </Form.Item>

        <Form.Item name="currency" label="币种" initialValue="CNY">
          <Select
            options={[
              { value: 'CNY', label: '人民币 (CNY)' },
              { value: 'USD', label: '美元 (USD)' },
            ]}
          />
        </Form.Item>

        <Form.Item
          name="payment_method"
          label="支付方式"
          rules={[{ required: true, message: '请选择支付方式' }]}
        >
          <Select
            placeholder="请选择"
            options={[
              { value: 'BANK_TRANSFER', label: '银行转账' },
              { value: 'INTERNAL_TRANSFER', label: '内部转账' },
            ]}
          />
        </Form.Item>

        <Form.Item name="memo" label="备注">
          <Input.TextArea rows={3} placeholder="付款备注 (可选)" />
        </Form.Item>

        <Form.Item name="callback_url" label="回调地址">
          <Input placeholder="https://your-domain.com/webhook (可选)" />
        </Form.Item>

        <Form.Item>
          <Button type="primary" htmlType="submit" block size="large">
            确认支付
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}

export default PaymentCreatePage;
