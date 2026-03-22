import { Layout, Menu } from 'antd';
import {
  TransactionOutlined,
  BankOutlined,
  DashboardOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';

const { Header, Sider, Content } = Layout;

function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();

  const menuItems = [
    {
      key: '/payments',
      icon: <TransactionOutlined />,
      label: '支付管理',
    },
    {
      key: '/payments/create',
      icon: <DashboardOutlined />,
      label: '发起支付',
    },
    {
      key: '/accounts',
      icon: <BankOutlined />,
      label: '账户管理',
    },
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" width={220}>
        <div style={{ height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <h1 style={{ color: '#fff', margin: 0, fontSize: 20 }}>PayFlow</h1>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', padding: '0 24px', borderBottom: '1px solid #f0f0f0' }}>
          <h2 style={{ margin: 0, lineHeight: '64px' }}>银行支付流系统</h2>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#fff', borderRadius: 8 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

export default MainLayout;
