import { useTranslation } from 'react-i18next';
import {
  Car,
  FileText,
  AlertTriangle,
  CreditCard,
  Calendar,
  FileCheck,
  TrendingUp,
  Clock,
  Shield,
  ArrowRight,
  Plus,
  Search,
  Filter,
  Download,
  MoreVertical,
  CheckCircle,
  AlertCircle,
  XCircle,
  Hourglass,
  Wallet,
} from 'lucide-react';
import { Link } from 'react-router-dom';

export function PortalDashboard() {
  const { t } = useTranslation();

  const quickActions = [
    { icon: Car, label: t('renewRegistration') || 'Renew Registration', href: '/portal/services', color: 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400' },
    { icon: FileText, label: t('renewLicence') || 'Renew Licence', href: '/portal/services', color: 'bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400' },
    { icon: AlertTriangle, label: t('payViolation') || 'Pay Violation', href: '/portal/violations', color: 'bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400' },
    { icon: Calendar, label: t('bookAppointment') || 'Book Appointment', href: '/portal/appointments', color: 'bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400' },
    { icon: CreditCard, label: t('makePayment') || 'Make Payment', href: '/portal/payments', color: 'bg-orange-50 text-orange-600 dark:bg-orange-900/20 dark:text-orange-400' },
    { icon: FileCheck, label: t('uploadDocument') || 'Upload Document', href: '/portal/documents', color: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/20 dark:text-indigo-400' },
  ];

  const stats = [
    { label: t('activeLicences') || 'Active Licences', value: '2', icon: FileText, color: 'text-green-600' },
    { label: t('registeredVehicles') || 'Registered Vehicles', value: '1', icon: Car, color: 'text-blue-600' },
    { label: t('unpaidViolations') || 'Unpaid Violations', value: '0', icon: AlertTriangle, color: 'text-red-600' },
    { label: t('upcomingAppointments') || 'Upcoming Appointments', value: '1', icon: Calendar, color: 'text-purple-600' },
  ];

  const recentActivity = [
    { id: 1, type: 'licence_renewal', title: t('licenceRenewed') || 'Licence Renewed', description: t('licenceRenewalDesc') || 'Your driving licence has been renewed successfully', time: '2 hours ago', status: 'completed', icon: CheckCircle, color: 'text-green-600 bg-green-50' },
    { id: 2, type: 'violation_payment', title: t('violationPaid') || 'Violation Paid', description: t('violationPaidDesc') || 'Fine for violation V-2024-001234 paid', time: '1 day ago', status: 'completed', icon: CreditCard, color: 'text-blue-600 bg-blue-50' },
    { id: 3, type: 'appointment_booked', title: t('appointmentBooked') || 'Appointment Booked', description: t('appointmentBookedDesc') || 'Vehicle inspection at Dubai Centre - Tomorrow 10:00 AM', time: '2 days ago', status: 'upcoming', icon: Calendar, color: 'text-purple-600 bg-purple-50' },
    { id: 4, type: 'document_upload', title: t('documentUploaded') || 'Document Uploaded', description: t('documentUploadDesc') || 'Insurance certificate uploaded for vehicle renewal', time: '3 days ago', status: 'processing', icon: Hourglass, color: 'text-orange-600 bg-orange-50' },
  ];

  const expiringSoon = [
    { id: 1, type: 'vehicle_registration', title: t('vehicleRegistration') || 'Vehicle Registration', expiry: '2024-12-15', daysLeft: 25, status: 'warning', plate: 'A 12345' },
    { id: 2, type: 'insurance', title: t('vehicleInsurance') || 'Vehicle Insurance', expiry: '2024-12-20', daysLeft: 30, status: 'ok', plate: 'A 12345' },
    { id: 3, type: 'driving_licence', title: t('drivingLicence') || 'Driving Licence', expiry: '2025-01-10', daysLeft: 51, status: 'ok', plate: '' },
  ];

  return (
    <div className="space-y-8">
      {/* Welcome Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('welcomeBack') || 'Welcome back'}</h1>
          <p className="text-text-secondary mt-1">{t('dashboardSubtitle') || 'Here\'s an overview of your traffic services'}</p>
        </div>
        <div className="flex items-center gap-3">
          <Link
            to="/portal/services"
            className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors"
          >
            <Plus className="w-4 h-4" />
            {t('startNewService') || 'New Service'}
          </Link>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map((stat, index) => (
          <Link key={index} to={`/portal/${stat.label.toLowerCase().replace(/\s+/g, '')}`} className="group p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all duration-300">
            <div className="flex items-center justify-between">
              <div className={`p-3 rounded-xl ${stat.color} bg-current/10`}>
                <stat.icon className="w-6 h-6" aria-hidden="true" />
              </div>
              <span className="text-2xl font-bold text-text group-hover:text-primary transition-colors">{stat.value}</span>
            </div>
            <p className="mt-3 text-sm text-text-secondary">{stat.label}</p>
          </Link>
        ))}
      </div>

      {/* Quick Actions */}
      <section>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-text">{t('quickActions') || 'Quick Actions'}</h2>
          <Link to="/portal/services" className="text-sm text-primary hover:underline flex items-center gap-1">
            {t('viewAll') || 'View all'}
            <ArrowRight className="w-4 h-4" />
          </Link>
        </div>
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          {quickActions.map((action, index) => (
            <Link key={index} to={action.href} className="group p-4 bg-surface rounded-xl border border-border hover:border-primary/50 hover:shadow-md transition-all duration-300">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center mb-3 ${action.color}`}>
                <action.icon className="w-5 h-5" aria-hidden="true" />
              </div>
              <h3 className="font-medium text-text group-hover:text-primary transition-colors">{action.label}</h3>
            </Link>
          ))}
        </div>
      </section>

      {/* Expiring Soon & Recent Activity */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Expiring Soon */}
        <section className="bg-surface rounded-2xl border border-border p-6">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-text">{t('expiringSoon') || 'Expiring Soon'}</h2>
            <Link to="/portal/profile" className="text-sm text-primary hover:underline">{t('viewAll') || 'View all'}</Link>
          </div>
          <div className="space-y-3">
            {expiringSoon.map((item) => (
              <div key={item.id} className="flex items-center justify-between p-4 bg-bg rounded-xl border border-border">
                <div className="flex items-center gap-3">
                  <div className={`p-2 rounded-lg ${item.status === 'warning' ? 'bg-orange-50 text-orange-600' : 'bg-green-50 text-green-600'}`}>
                    {item.type === 'vehicle_registration' && <Car className="w-5 h-5" />}
                    {item.type === 'insurance' && <Shield className="w-5 h-5" />}
                    {item.type === 'driving_licence' && <FileText className="w-5 h-5" />}
                  </div>
                  <div>
                    <p className="font-medium text-text">{item.title}</p>
                    {item.plate && <p className="text-sm text-text-secondary">{item.plate}</p>}
                  </div>
                </div>
                <div className="text-right">
                  <p className={`font-medium ${item.status === 'warning' ? 'text-orange-600' : 'text-green-600'}`}>
                    {item.daysLeft} {t('days') || 'days'}
                  </p>
                  <p className="text-xs text-text-secondary">{new Date(item.expiry).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}</p>
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* Recent Activity */}
        <section className="bg-surface rounded-2xl border border-border p-6">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-text">{t('recentActivity') || 'Recent Activity'}</h2>
            <Link to="/portal/documents" className="text-sm text-primary hover:underline">{t('viewAll') || 'View all'}</Link>
          </div>
          <div className="space-y-4">
            {recentActivity.map((activity) => (
              <div key={activity.id} className="flex items-start gap-3 p-3 bg-bg rounded-xl border border-border">
                <div className={`p-2 rounded-lg ${activity.color}`}>
                  <activity.icon className="w-5 h-5" aria-hidden="true" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-text">{activity.title}</p>
                  <p className="text-sm text-text-secondary">{activity.description}</p>
                </div>
                <div className="flex flex-col items-end gap-1">
                  <span className={`inline-flex items-center px-2 py-1 rounded-full text-xs font-medium ${
                    activity.status === 'completed' ? 'bg-green-50 text-green-700' :
                    activity.status === 'upcoming' ? 'bg-purple-50 text-purple-700' :
                    'bg-orange-50 text-orange-700'
                  }`}>
                    {activity.status === 'completed' && t('completed') || 'Completed'}
                    {activity.status === 'upcoming' && t('upcoming') || 'Upcoming'}
                    {activity.status === 'processing' && t('processing') || 'Processing'}
                  </span>
                  <span className="text-xs text-text-muted">{activity.time}</span>
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>

      {/* Notifications / Alerts */}
      <section className="bg-surface rounded-2xl border border-border p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-text">{t('notifications') || 'Notifications & Alerts'}</h2>
          <Link to="/portal/profile" className="text-sm text-primary hover:underline">{t('manage') || 'Manage'}</Link>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="p-4 bg-blue-50 border border-blue-200 dark:bg-blue-900/20 dark:border-blue-800 rounded-xl">
            <div className="flex items-start gap-3">
              <div className="p-2 rounded-lg bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
                <TrendingUp className="w-5 h-5" />
              </div>
              <div>
                <h3 className="font-medium text-text">{t('newServiceAvailable') || 'New Service Available'}</h3>
                <p className="text-sm text-text-secondary mt-1">{t('newServiceDesc') || 'Digital licence renewal now available without visiting a centre'}</p>
              </div>
            </div>
          </div>
          <div className="p-4 bg-green-50 border border-green-200 dark:bg-green-900/20 dark:border-green-800 rounded-xl">
            <div className="flex items-start gap-3">
              <div className="p-2 rounded-lg bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
                <CheckCircle className="w-5 h-5" />
              </div>
              <div>
                <h3 className="font-medium text-text">{t('paymentConfirmed') || 'Payment Confirmed'}</h3>
                <p className="text-sm text-text-secondary mt-1">{t('paymentConfirmedDesc') || 'Your payment for vehicle registration has been processed successfully'}</p>
              </div>
            </div>
          </div>
          <div className="p-4 bg-orange-50 border border-orange-200 dark:bg-orange-900/20 dark:border-orange-800 rounded-xl">
            <div className="flex items-start gap-3">
              <div className="p-2 rounded-lg bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400">
                <Clock className="w-5 h-5" />
              </div>
              <div>
                <h3 className="font-medium text-text">{t('appointmentReminder') || 'Appointment Reminder'}</h3>
                <p className="text-sm text-text-secondary mt-1">{t('appointmentReminderDesc') || 'Vehicle inspection tomorrow at 10:00 AM - Dubai Centre'}</p>
              </div>
            </div>
          </div>
          <div className="p-4 bg-red-50 border border-red-200 dark:bg-red-900/20 dark:border-red-800 rounded-xl">
            <div className="flex items-start gap-3">
              <div className="p-2 rounded-lg bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400">
                <AlertCircle className="w-5 h-5" />
              </div>
              <div>
                <h3 className="font-medium text-text">{t('documentExpiring') || 'Document Expiring'}</h3>
                <p className="text-sm text-text-secondary mt-1">{t('documentExpiringDesc') || 'Your insurance certificate expires in 7 days. Please renew.'}</p>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}