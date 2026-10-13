import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AlertTriangle, CreditCard, Search, Filter, Clock, CheckCircle, AlertCircle, XCircle, FileText, Gavel, Calendar, DollarSign, TrendingUp } from 'lucide-react';

export function PortalViolations() {
  const { t } = useTranslation();

  const violations = [
    { id: 1, number: 'V-2024-001234', plate: 'A 12345', date: '2024-11-15', location: 'Sheikh Zayed Road, Dubai', type: 'Speeding (80 in 60 zone)', fine: 600, points: 4, status: 'unpaid', dueDate: '2024-12-15' },
    { id: 2, number: 'V-2024-001235', plate: 'A 12345', date: '2024-10-20', location: 'Al Khail Road, Dubai', type: 'Red Light Violation', fine: 1000, points: 8, status: 'paid', dueDate: '2024-11-19', paidDate: '2024-10-22' },
    { id: 3, number: 'V-2024-001236', plate: 'B 67890', date: '2024-11-01', location: 'Emirates Road, Abu Dhabi', type: 'Illegal Parking', fine: 400, points: 2, status: 'objection_pending', dueDate: '2024-12-01' },
  ];

  const totalUnpaid = violations.filter(v => v.status === 'unpaid').reduce((sum, v) => sum + v.fine, 0);
  const totalPoints = violations.filter(v => v.status !== 'paid').reduce((sum, v) => sum + v.points, 0);

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('myViolations') || 'My Violations'}</h1>
          <p className="text-text-secondary">{t('violationsSubtitle') || 'View and manage your traffic violations'}</p>
        </div>
        <div className="flex items-center gap-4">
          <Link to="/portal/services/violations/pay" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
            <CreditCard className="w-4 h-4" />
            {t('payViolation') || 'Pay Violation'}
          </Link>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('unpaidViolations') || 'Unpaid Violations'}</p>
              <p className="text-2xl font-bold text-text mt-1">{violations.filter(v => v.status === 'unpaid').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400">
              <AlertTriangle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('totalFines') || 'Total Fines Due'}</p>
              <p className="text-2xl font-bold text-text mt-1">AED {totalUnpaid.toLocaleString()}</p>
            </div>
            <div className="p-3 rounded-xl bg-orange-50 text-orange-600 dark:bg-orange-900/20 dark:text-orange-400">
              <DollarSign className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('blackPoints') || 'Black Points'}</p>
              <p className="text-2xl font-bold text-text mt-1">{totalPoints}</p>
            </div>
            <div className="p-3 rounded-xl bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400">
              <TrendingUp className="w-6 h-6" />
            </div>
          </div>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-col sm:flex-row gap-4 mb-6">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
          <input type="search" placeholder={t('searchViolations') || 'Search violations...'} className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
        </div>
        <select className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByStatus')}>
          <option value="all">{t('allStatus') || 'All Status'}</option>
          <option value="unpaid">{t('unpaid') || 'Unpaid'}</option>
          <option value="paid">{t('paid') || 'Paid'}</option>
          <option value="objection_pending">{t('objectionPending') || 'Objection Pending'}</option>
          <option value="instalment">{t('instalment') || 'Instalment'}</option>
        </select>
      </div>

      {/* Violations Table */}
      <div className="bg-surface rounded-2xl border border-border overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border bg-bg">
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('violationNumber') || 'Violation #'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('date') || 'Date'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('plate') || 'Plate'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('type') || 'Type'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('location') || 'Location'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('fine') || 'Fine'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('points') || 'Points'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('status') || 'Status'}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('actions') || 'Actions'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {violations.map((violation) => (
                <tr key={violation.id} className="hover:bg-bg transition-colors">
                  <td className="px-6 py-4">
                    <code className="text-sm font-mono text-text">{violation.number}</code>
                  </td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{new Date(violation.date).toLocaleDateString()}</td>
                  <td className="px-6 py-4 text-sm font-medium text-text">{violation.plate}</td>
                  <td className="px-6 py-4 text-sm text-text">{violation.type}</td>
                  <td className="px-6 py-4 text-sm text-text-secondary max-w-xs truncate">{violation.location}</td>
                  <td className="px-6 py-4 text-sm font-semibold text-text">AED {violation.fine.toLocaleString()}</td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{violation.points} {t('points') || 'pts'}</td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${
                      violation.status === 'unpaid' ? 'bg-red-50 text-red-700' :
                      violation.status === 'paid' ? 'bg-green-50 text-green-700' :
                      violation.status === 'objection_pending' ? 'bg-yellow-50 text-yellow-700' :
                      'bg-blue-50 text-blue-700'
                    }`}>
                      {t(violation.status) || violation.status}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      {violation.status === 'unpaid' && (
                        <Link to={`/portal/services/violations/pay/${violation.id}`} className="px-3 py-1.5 text-xs font-medium text-white bg-primary rounded-lg hover:bg-primary-dark transition-colors">
                          <CreditCard className="w-3 h-3 mr-1" />
                          {t('pay') || 'Pay'}
                        </Link>
                      )}
                      {violation.status !== 'paid' && (
                        <Link to={`/portal/services/violations/object/${violation.id}`} className="px-3 py-1.5 text-xs font-medium text-text-secondary bg-surface border border-border rounded-lg hover:bg-surface-hover hover:border-primary/50 transition-colors">
                          <Gavel className="w-3 h-3 mr-1" />
                          {t('object') || 'Object'}
                        </Link>
                      )}
                      <button className="px-3 py-1.5 text-xs font-medium text-text-secondary bg-surface border border-border rounded-lg hover:bg-surface-hover hover:border-primary/50 transition-colors">
                        <FileText className="w-3 h-3 mr-1" />
                        {t('details') || 'Details'}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Summary Stats */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Link to="/portal/services/violations/pay" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('payFines') || 'Pay Fines'}</p>
              <p className="text-2xl font-bold text-text mt-1">AED {totalUnpaid.toLocaleString()}</p>
            </div>
            <div className="p-3 rounded-xl bg-primary/10 text-primary">
              <CreditCard className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/portal/services/violations/object" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('fileObjection') || 'File Objection'}</p>
              <p className="text-2xl font-bold text-text mt-1">{violations.filter(v => v.status === 'objection_pending').length} {t('pending')}</p>
            </div>
            <div className="p-3 rounded-xl bg-yellow-50 text-yellow-600 dark:bg-yellow-900/20 dark:text-yellow-400">
              <Gavel className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/portal/services/violations/instalments" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('instalmentPlans') || 'Instalment Plans'}</p>
              <p className="text-2xl font-bold text-text mt-1">{violations.filter(v => v.status === 'instalment').length} {t('active')}</p>
            </div>
            <div className="p-3 rounded-xl bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400">
              <Calendar className="w-6 h-6" />
            </div>
          </div>
        </Link>
      </div>
    </div>
  );
}