import { Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import MainLayout from './layouts/MainLayout';
import PaymentListPage from './pages/PaymentListPage';
import PaymentCreatePage from './pages/PaymentCreatePage';
import PaymentDetailPage from './pages/PaymentDetailPage';
import AccountPage from './pages/AccountPage';

function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <Routes>
        <Route path="/" element={<MainLayout />}>
          <Route index element={<Navigate to="/payments" replace />} />
          <Route path="payments" element={<PaymentListPage />} />
          <Route path="payments/create" element={<PaymentCreatePage />} />
          <Route path="payments/:paymentId" element={<PaymentDetailPage />} />
          <Route path="accounts" element={<AccountPage />} />
        </Route>
      </Routes>
    </ConfigProvider>
  );
}

export default App;
