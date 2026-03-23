import { useState, useEffect } from 'react';
import { Card, Input, Table, Tag, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { listAccounts } from '../api/account';
import type { AccountBalance } from '../types';
import { formatAmount, formatTime } from '../utils/format';

function AccountPage() {
  const [accounts, setAccounts] = useState<AccountBalance[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');

  useEffect(() => {
    listAccounts()
      .then(setAccounts)
      .catch(() => message.error('账户列表加载失败'))
      .finally(() => setLoading(false));
  }, []);

  const filtered = search.trim()
    ? accounts.filter(
        (a) =>
          a.account_id.toLowerCase().includes(search.toLowerCase()) ||
          a.account_name.toLowerCase().includes(search.toLowerCase()),
      )
    : accounts;

  return (
    <Card
      title="账户列表"
      extra={
        <Input
          placeholder="搜索账户ID或名称"
          prefix={<SearchOutlined />}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          allowClear
          style={{ width: 240 }}
        />
      }
    >
      <Table
        rowKey="account_id"
        loading={loading}
        dataSource={filtered}
        pagination={false}
        columns={[
          { title: '账户ID', dataIndex: 'account_id' },
          { title: '账户名', dataIndex: 'account_name' },
          {
            title: '可用余额',
            dataIndex: 'available_balance',
            render: (v: number, r: AccountBalance) => (
              <span style={{ color: '#52c41a', fontWeight: 600 }}>
                {formatAmount(v)} {r.currency}
              </span>
            ),
          },
          {
            title: '冻结金额',
            dataIndex: 'frozen_balance',
            render: (v: number, r: AccountBalance) =>
              v > 0 ? (
                <Tag color="orange">{formatAmount(v)} {r.currency}</Tag>
              ) : (
                <span style={{ color: '#999' }}>—</span>
              ),
          },
          {
            title: '更新时间',
            dataIndex: 'updated_at',
            render: (v: string) => formatTime(v),
          },
        ]}
      />
    </Card>
  );
}

export default AccountPage;
