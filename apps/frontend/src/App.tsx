import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './hooks/useAuth';
import { Layout } from './components/layout/Layout';
import { PublicLayout } from './components/layout/PublicLayout';
import { Login } from './pages/auth/Login';
import { Register } from './pages/auth/Register';
import { PortalDashboard } from './pages/portal/Dashboard';
import { PortalServices } from './pages/portal/Services';
import { PortalLicences } from './pages/portal/Licences';
import { PortalVehicles } from './pages/portal/Vehicles';
import { PortalViolations } from './pages/portal/Violations';
import { PortalPayments } from './pages/portal/Payments';
import { PortalAppointments } from './pages/portal/Appointments';
import { PortalDocuments } from './pages/portal/Documents';
import { PortalProfile } from './pages/portal/Profile';
import { PortalSettings } from './pages/portal/Settings';
import { PublicHome } from './pages/public/Home';
import { PublicServiceGuide } from './pages/public/ServiceGuide';
import { PublicFeeCalculator } from './pages/public/FeeCalculator';
import { PublicCentreFinder } from './pages/public/CentreFinder';
import { PublicCertificateVerify } from './pages/public/CertificateVerify';
import { PublicViolationEnquiry } from './pages/public/ViolationEnquiry';
import { BackofficeDashboard } from './pages/backoffice/Dashboard';
import { BackofficeServices } from './pages/backoffice/Services';
import { BackofficeTransactions } from './pages/backoffice/Transactions';
import { BackofficeUsers } from './pages/backoffice/Users';
import { BackofficeReports } from './pages/backoffice/Reports';
import { NotFound } from './pages/common/NotFound';

function PrivateRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, loading } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center" role="status" aria-label="Loading">
        <div className="animate-spin rounded-full h-12 w-12 border-4 border-primary border-t-transparent" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, loading } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center" role="status" aria-label="Loading">
        <div className="animate-spin rounded-full h-12 w-12 border-4 border-primary border-t-transparent" />
      </div>
    );
  }

  if (isAuthenticated) {
    return <Navigate to="/portal" replace />;
  }

  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      {/* Public routes */}
      <Route path="/login" element={<PublicRoute><Login /></PublicRoute>} />
      <Route path="/register" element={<PublicRoute><Register /></PublicRoute>} />

      {/* Public pages (no auth required) */}
      <Route element={<PublicLayout />}>
        <Route path="/" element={<PublicHome />} />
        <Route path="/services" element={<PublicServiceGuide />} />
        <Route path="/fee-calculator" element={<PublicFeeCalculator />} />
        <Route path="/centre-finder" element={<PublicCentreFinder />} />
        <Route path="/certificate-verify" element={<PublicCertificateVerify />} />
        <Route path="/violation-enquiry" element={<PublicViolationEnquiry />} />
      </Route>

      {/* Portal (customer) routes */}
      <Route element={<PrivateRoute><Layout /></PrivateRoute>}>
        <Route path="/portal" element={<Navigate to="/portal/dashboard" replace />} />
        <Route path="/portal/dashboard" element={<PortalDashboard />} />
        <Route path="/portal/services" element={<PortalServices />} />
        <Route path="/portal/licences" element={<PortalLicences />} />
        <Route path="/portal/vehicles" element={<PortalVehicles />} />
        <Route path="/portal/violations" element={<PortalViolations />} />
        <Route path="/portal/payments" element={<PortalPayments />} />
        <Route path="/portal/appointments" element={<PortalAppointments />} />
        <Route path="/portal/documents" element={<PortalDocuments />} />
        <Route path="/portal/profile" element={<PortalProfile />} />
        <Route path="/portal/settings" element={<PortalSettings />} />
      </Route>

      {/* Backoffice (agent) routes */}
      <Route
        element={
          <PrivateRoute>
            <Layout backoffice />
          </PrivateRoute>
        }
      >
        <Route path="/backoffice" element={<Navigate to="/backoffice/dashboard" replace />} />
        <Route path="/backoffice/dashboard" element={<BackofficeDashboard />} />
        <Route path="/backoffice/services" element={<BackofficeServices />} />
        <Route path="/backoffice/transactions" element={<BackofficeTransactions />} />
        <Route path="/backoffice/users" element={<BackofficeUsers />} />
        <Route path="/backoffice/reports" element={<BackofficeReports />} />
      </Route>

      {/* 404 */}
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}