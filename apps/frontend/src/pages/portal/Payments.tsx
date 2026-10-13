import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { CreditCard, Banknote, Wallet, Receipt, Search, Filter, Calendar, Plus, Download, Eye, TrendingUp, Clock, CheckCircle, XCircle, Hourglass, AlertCircle, ArrowRight } from 'lucide-react';

export function PortalPayments() {
  const { t } = useTranslation();

  const payments = [
    { id: 'PAY-2024-001234', date: '2024-11-20', service: 'Vehicle Registration Renewal', amount: 450, status: 'completed', method: 'Credit Card', reference: 'TXN-789456' },
    { id: 'PAY-2024-001235', date: '2024-11-15', service: 'Violation Fine V-2024-001235', amount: 1000, status: 'completed', method: 'Bank Transfer', reference: 'TXN-789457' },
    { id: 'PAY-2024-001236', date: '2024-11-10', service: 'Licence Renewal', amount: 300, status: 'completed', method: 'Digital Wallet', reference: 'TXN-789458' },
    { id: 'PAY-2024-001237', date: '2024-11-05', service: 'Vehicle Inspection', amount: 150, status: 'pending', method: 'Credit Card', reference: 'TXN-789459' },
    { id: 'PAY-2024-001238', date: '2024-11-01', service: 'Plate Replacement', amount: 200, status: 'failed', method: 'Bank Transfer', reference: 'TXN-789460' },
  ];

  const totalPaid = payments.filter(p => p.status === 'completed').reduce((sum, p) => sum + p.amount, 0);
  const totalPending = payments.filter(p => p.status === 'pending').reduce((sum, p) => sum + p.amount, 0);

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('myPayments') || 'My Payments'}</h1>
          <p className="text-text-secondary">{t('paymentsSubtitle') || 'View and manage your payment history'}</p>
        </div>
        <div className="flex items-center gap-3">
          <Link to="/portal/services/payments/make" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
            <Plus className="w-4 h-4" />
            {t('makePayment') || 'Make Payment'}
          </Link>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('totalPaid') || 'Total Paid'}</p>
              <p className="text-2xl font-bold text-text mt-1">AED {totalPaid.toLocaleString()}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <CheckCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('pendingPayments') || 'Pending'}</p>
              <p className="text-2xl font-bold text-text mt-1">AED {totalPending.toLocaleString()}</p>
            </div>
            <div className="p-3 rounded-xl bg-orange-50 text-orange-600 dark:bg-orange-900/20 dark:text-orange-400">
              <Hourglass className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('thisMonth') || 'This Month'}</p>
              <p className="text-2xl font-bold text-text mt-1">AED {payments.filter(p => new Date(p.date).getMonth() === new Date().getMonth() && p.status === 'completed').reduce((sum, p) => sum + p.amount, 0).toLocaleString()}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <Calendar className="w-6 h-6" />
            </div>
          </div>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-col sm:flex-row gap-4 mb-6">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
          <input type="search" placeholder={t('searchPayments') || 'Search payments...'} className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
        </div>
        <select className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByStatus')}>
          <option value="all">{t('allStatus') || 'All'}</option>
          <option value="completed">{t('completed') || 'Completed'}</option>
          <option value="pending">{t('pending') || 'Pending'}</option>
          <option value="failed">{t('failed') || 'Failed'}</option>
          <option value="refunded">{t('refunded') || 'Refunded'}</option>
        </select>
        <select className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByMethod')}>
          <option value="all">{t('allMethods') || 'All Methods'}</option>
          <option value="credit_card">{t('creditCard') || 'Credit Card'}</option>
          <option value="bank_transfer">{t('bankTransfer') || 'Bank Transfer'}</option>
          <option value="wallet">{t('wallet') || 'Digital Wallet'}</option>
          <option value="cash">{t('cash') || 'Cash'}</option>
        </select>
      </div>

      {/* Payments Table */}
      <div className="bg-surface rounded-2xl border border-border overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border bg-bg">
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('date') || 'Date'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('service') || 'Service'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('amount') || 'Amount'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('method') || 'Method'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('status') || 'Status'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('reference') || 'Reference'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('actions') || 'Actions'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {payments.map((payment) => (
                <tr key={payment.id} className="hover:bg-bg transition-colors">
                  <td className="px-6 py-4 text-sm text-text-secondary">{new Date(payment.date).toLocaleDateString()}</td>
                  <td className="px-6 py-4 text-sm text-text">{payment.service}</td>
                  <td className="px-6 py-4 text-sm font-semibold text-text">AED {payment.amount.toLocaleString()}</td>
                  <td className="px-6 py-4">
                    <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-400">
                      <Wallet className="w-3 h-3 mr-1" />
                      {payment.method}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${
                      payment.status === 'completed' ? 'bg-green-50 text-green-700' :
                      payment.status === 'pending' ? 'bg-yellow-50 text-yellow-700' :
                      'bg-red-50 text-red-700'
                    }`}>
                      {t(payment.status) || payment.status}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm font-mono text-text-secondary">{payment.reference}</td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <Link to={`/portal/payments/${payment.id}`} className="px-3 py-1.5 text-xs font-medium text-primary hover:underline flex items-center gap-1">
                        <Eye className="w-3 h-3" />
                        {t('view') || 'View'}
                      </Link>
                      {payment.status === 'completed' && (
                        <button className="px-3 py-1.5 text-xs font-medium text-primary hover:underline flex items-center gap-1">
                          <Download className="w-3 h-3 mr-1" />
                          {t('receipt') || 'Receipt'}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Quick Actions */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Link to="/portal/services/payments/make" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('makePayment') || 'Make Payment'}</p>
              <p className="text-2xl font-bold text-text mt-1">{t('payNow') || 'Pay Now'}</p>
            </div>
            <div className="p-3 rounded-xl bg-primary/10 text-primary">
              <Plus className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/portal/payments/history" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('paymentHistory') || 'Payment History'}</p>
              <p className="text-2xl font-bold text-text mt-1">{payments.length} {t('transactions')}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <TrendingUp className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/portal/payments/refunds" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('refundRequests') || 'Refund Requests'}</p>
              <p className="text-2xl font-bold text-text mt-1">0 {t('pending')}</p>
            </div>
            <div className="p-3 rounded-xl bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400">
              <ArrowRight className="w-6 h-6" />
            </div>
          </div>
        </Link>
      </div>
    </div>
  );
}