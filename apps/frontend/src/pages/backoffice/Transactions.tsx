import { useTranslation } from 'react-i18next';
import { Search, Filter, Download, Eye, Edit, MoreVertical, CreditCard, Calendar, DollarSign, TrendingUp, CheckCircle, XCircle, AlertCircle, Clock, Hourglass, FileText, User, Building2, Car, Shield, Plus, Filter as FilterIcon } from 'lucide-react';
import { useState } from 'react';

export function BackofficeTransactions() {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [serviceFilter, setServiceFilter] = useState('all');
  const [dateRange, setDateRange] = useState('today');

  const transactions = [
    { id: 'TXN-2024-001234', ref: 'VEH_REG_RENEW-R1-2024-000123', service: 'Vehicle Registration Renewal', customer: 'Ahmed Al Mansouri', centre: 'Dubai Centre', channel: 'PORTAL', amount: 450, status: 'completed', paymentMethod: 'Credit Card', date: '2024-12-15 10:30', agent: 'Sarah Johnson' },
    { id: 'TXN-2024-001235', ref: 'LIC_RENEW-R2-2024-000456', service: 'Licence Renewal', customer: 'Sarah Johnson', centre: 'Abu Dhabi Centre', channel: 'BACKOFFICE', amount: 300, status: 'completed', paymentMethod: 'Bank Transfer', date: '2024-12-15 10:15', agent: 'Mohammed Ali' },
    { id: 'TXN-2024-001236', ref: 'VEH_REG_NEW-R1-2024-000789', service: 'New Vehicle Registration', customer: 'Mohammed Al Zahrani', centre: 'Dubai Centre', channel: 'BACKOFFICE', amount: 1200, status: 'in_progress', paymentMethod: 'Credit Card', date: '2024-12-15 10:10', agent: 'Fatima Al Suwaidi' },
    { id: 'TXN-2024-001237', ref: 'VIO_PAY-R3-2024-000321', service: 'Violation Payment', customer: 'Fatima Al Suwaidi', centre: 'Sharjah Centre', channel: 'PORTAL', amount: 600, status: 'completed', paymentMethod: 'Digital Wallet', date: '2024-12-15 10:05', agent: 'System' },
    { id: 'TXN-2024-001238', ref: 'VEH_TRANSFER-R1-2024-000654', service: 'Vehicle Ownership Transfer', customer: 'Omar Hassan', centre: 'Ajman Centre', channel: 'BACKOFFICE', amount: 350, status: 'pending', paymentMethod: 'Pending', date: '2024-12-15 09:55', agent: 'Omar Al Zaabi' },
    { id: 'TXN-2024-001239', ref: 'LIC_NEW-R2-2024-000987', service: 'New Driving Licence Issue', customer: 'Aisha Al Maktoum', centre: 'Dubai Centre', channel: 'BACKOFFICE', amount: 600, status: 'pending', paymentMethod: 'Pending', date: '2024-12-15 09:45', agent: 'Khalid Al Marri' },
    { id: 'TXN-2024-001240', ref: 'VIO_OBJ-R3-2024-000147', service: 'Violation Objection', customer: 'Khalid Al Qasimi', centre: 'Dubai Centre', channel: 'PORTAL', amount: 50, status: 'completed', paymentMethod: 'Credit Card', date: '2024-12-15 09:30', agent: 'System' },
    { id: 'TXN-2024-001241', ref: 'APPT_BOOK-R1-2024-000258', service: 'Appointment Booking', customer: 'Yousef Al Nuaimi', centre: 'Dubai Centre', channel: 'APP', amount: 0, status: 'completed', paymentMethod: 'N/A', date: '2024-12-15 09:15', agent: 'System' },
    { id: 'TXN-2024-001242', ref: 'PLATE_NEW-R1-2024-000369', service: 'New Plate Registration', customer: 'Abdullah Al Falasi', centre: 'Dubai Centre', channel: 'KIOSK', amount: 200, status: 'failed', paymentMethod: 'Credit Card', date: '2024-12-15 09:00', agent: 'System' },
    { id: 'TXN-2024-001243', ref: 'VEH_REG_RENEW-R2-2024-000567', service: 'Vehicle Registration Renewal', customer: 'Layla Al Marzooqi', centre: 'Abu Dhabi Centre', channel: 'PORTAL', amount: 350, status: 'completed', paymentMethod: 'Bank Transfer', date: '2024-12-15 08:45', agent: 'System' },
  ];

  const statuses = ['all', 'completed', 'in_progress', 'pending', 'failed', 'cancelled'];
  const services = ['all', 'Vehicle Registration', 'Licence', 'Violations', 'Plates', 'Appointments', 'Payments'];

  const todayStats = {
    total: transactions.length,
    completed: transactions.filter(t => t.status === 'completed').length,
    inProgress: transactions.filter(t => t.status === 'in_progress').length,
    pending: transactions.filter(t => t.status === 'pending').length,
    failed: transactions.filter(t => t.status === 'failed').length,
    revenue: transactions.filter(t => t.status === 'completed').reduce((sum, t) => sum + t.amount, 0),
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('transactions') || 'Transactions'}</h1>
          <p className="text-text-secondary">{t('transactionsSubtitle') || 'Monitor and manage all service transactions'}</p>
        </div>
        <div className="flex items-center gap-3">
          <button className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
            <Download className="w-4 h-4 mr-2" />
            {t('export') || 'Export'}
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-6 gap-4">
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('totalTransactions')}</p>
              <p className="text-2xl font-bold text-text mt-1">{todayStats.total}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <FileText className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('completed')}</p>
              <p className="text-2xl font-bold text-text mt-1">{todayStats.completed}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <CheckCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('inProgress')}</p>
              <p className="text-2xl font-bold text-text mt-1">{todayStats.inProgress}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <Hourglass className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('pending')}</p>
              <p className="text-2xl font-bold text-text mt-1">{todayStats.pending}</p>
            </div>
            <div className="p-3 rounded-xl bg-yellow-50 text-yellow-600 dark:bg-yellow-900/20 dark:text-yellow-400">
              <Clock className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('failed')}</p>
              <p className="text-2xl font-bold text-text mt-1">{todayStats.failed}</p>
            </div>
            <div className="p-3 rounded-xl bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400">
              <XCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('revenue')}</p>
              <p className="text-2xl font-bold text-text mt-1">AED {todayStats.revenue.toLocaleString()}</p>
            </div>
            <div className="p-3 rounded-xl bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400">
              <DollarSign className="w-6 h-6" />
            </div>
          </div>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-col sm:flex-row gap-4 mb-6">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('searchTransactions') || 'Search transactions...'}
            className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
          />
        </div>
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByStatus')}>
          {['all', 'completed', 'in_progress', 'pending', 'failed', 'cancelled'].map(s => <option key={s} value={s}>{s === 'all' ? t('allStatus') : t(s)}</option>)}
        </select>
        <select value={serviceFilter} onChange={(e) => setServiceFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByService')}>
          {['all', 'Vehicle Registration', 'Licence', 'Violations', 'Plates', 'Appointments'].map(s => <option key={s} value={s}>{s === 'all' ? t('allServices') : t(s)}</option>)}
        </select>
        <select value={dateRange} onChange={(e) => setDateRange(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByDate')}>
          <option value="today">{t('today')}</option>
          <option value="week">{t('thisWeek')}</option>
          <option value="month">{t('thisMonth')}</option>
          <option value="custom">{t('customRange')}</option>
        </select>
      </div>

      {/* Transactions Table */}
      <div className="bg-surface rounded-2xl border border-border overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border bg-bg">
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('transactionId')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('reference')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('service')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('customer')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('centre')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('channel')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('amount')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('status')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('paymentMethod')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('date')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('agent')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('actions')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {transactions.filter(t => 
                (statusFilter === 'all' || t.status === statusFilter) &&
                (serviceFilter === 'all' || t.service === serviceFilter) &&
                (t.id.toLowerCase().includes(search.toLowerCase()) || t.customer.toLowerCase().includes(search.toLowerCase()) || t.ref.toLowerCase().includes(search.toLowerCase()))
              ).map((txn) => (
                <tr key={txn.id} className="hover:bg-bg transition-colors">
                  <td className="px-6 py-4">
                    <code className="text-sm font-mono text-text">{txn.id}</code>
                  </td>
                  <td className="px-6 py-4">
                    <code className="text-xs font-mono text-text-secondary">{txn.ref}</code>
                  </td>
                  <td className="px-6 py-4">
                    <p className="font-medium text-text">{txn.service}</p>
                  </td>
                  <td className="px-6 py-4">
                    <p className="font-medium text-text">{txn.customer}</p>
                  </td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{txn.centre}</td>
                  <td className="px-6 py-4">
                    <span className="inline-flex items-center px-2 py-1 rounded-full text-xs font-medium bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-400">
                      {txn.channel}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm font-semibold text-text">AED {txn.amount.toLocaleString()}</td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${
                      txn.status === 'completed' ? 'bg-green-50 text-green-700' :
                      txn.status === 'in_progress' ? 'bg-blue-50 text-blue-700' :
                      txn.status === 'pending' ? 'bg-yellow-50 text-yellow-700' :
                      txn.status === 'failed' ? 'bg-red-50 text-red-700' :
                      'bg-gray-50 text-gray-700'
                    }`}>
                      {txn.status.replace('_', ' ')}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{txn.paymentMethod}</td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{txn.date}</td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{txn.agent}</td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <button className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('view')}>
                        <Eye className="w-4 h-4" />
                      </button>
                      <button className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('receipt')}>
                        <FileText className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}