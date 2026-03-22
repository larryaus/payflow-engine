import { useState } from 'react';
import { Card, Input, Button, Descriptions, message, Spin } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { getAccountBalance } from '../api/account';
import type { AccountBalance } from '../types';
import { formatAmount, formatTime } from '../utils/format';

function AccountPage() {
  const [accountId, setAccountId] = useState('');
  const [balance, setBalance] = useState<AccountBalance | null>(null);
  const [loading, setLoading] = useState(false);

  const handleQuery = async () => {
    if (!accountId.trim()) {
      message.warning('请输入账户ID');
      return;
    }
    setLoading(true);
    try {
      const data = await getAccountBalance(accountId.trim());
      setBalance(data);
    } catch {
      message.error('账户查询失败');
      setBalance(null);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ maxWidth: 640, margin: '0 auto' }}>
      <Card title="账户余额查询">
        <div style={{ display: 'flex', gap: 12, marginBottom: 24 }}>
          <Input
            placeholder="输入账户ID，例: ACC_001"
            value={accountId}
            onChange={(e) => setAccountId(e.target.value)}
            onPressEnter={handleQuery}
            style={{ flex: 1 }}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleQuery} loading={loading}>
            查询
          </Button>
        </div>

        {loading && <Spin style={{ display: 'block', margin: '40px auto' }} />}

        {balance && !loading && (
          <Descriptions bordered column={1}>
            <Descriptions.Item label="账户ID">{balance.account_id}</Descriptions.Item>
            <Descriptions.Item label="可用余额">
              <span style={{ color: '#52c41a', fontWeight: 'bold', fontSize: 18 }}>
                {formatAmount(balance.available_balance)} {balance.currency}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="冻结金额">
              <span style={{ color: '#faad14' }}>
                {formatAmount(balance.frozen_balance)} {balance.currency}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="更新时间">{formatTime(balance.updated_at)}</Descriptions.Item>
          </Descriptions>
        )}
      </Card>
    </div>
  );
}

export default AccountPage;
