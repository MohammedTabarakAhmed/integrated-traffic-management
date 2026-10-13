import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Users, FileText, CreditCard, TrendingUp, Clock, CheckCircle, AlertCircle, XCircle, DollarSign, Building2, Car, FileCheck, Plus, Search, Filter, Download, Eye, MoreVertical, Calendar, Shield, BarChart2, Activity, AlertTriangle } from 'lucide-react';

export function BackofficeDashboard() {
  const { t } = useTranslation();

  const stats = [
    { label: 'Active Agents', value: '127', change: '+12%', trend: 'up', icon: Users, color: 'text-blue-600 bg-blue-50' },
    { label: 'Transactions Today', value: '1,234', change: '+8%', trend: 'up', icon: FileText, color: 'text-green-600 bg-green-50' },
    { label: 'Revenue', value: 'AED 2.4M', change: '+15%', trend: 'up', icon: DollarSign, color: 'text-purple-600 bg-purple-50' },
    { label: 'Avg Processing Time', value: '3.2 min', change: '-0.5 min', trend: 'down', icon: Clock, color: 'text-orange-600 bg-orange-50' },
  ];

  const recentActivity = [
    { id: 1, type: 'registration', title: 'Vehicle Registration', customer: 'Ahmed Al Mansouri', centre: 'Dubai Centre', time: '2 min ago', status: 'completed', icon: CheckCircle, color: 'text-green-600' },
    { id: 2, type: 'licence_renewal', title: 'Licence Renewal', customer: 'Sarah Johnson', centre: 'Abu Dhabi Centre', time: '5 min ago', status: 'in_progress', icon: AlertTriangle, color: 'text-yellow-600' },
    { id: 3, type: 'violation_payment', title: 'Violation Payment', customer: 'Mohammed Al Zahrani', centre: 'Sharjah Centre', time: '12 min ago', status: 'completed', icon: CheckCircle, color: 'text-green-600' },
    { id: 4, type: 'licence_issue', title: 'New Licence Issue', customer: 'Fatima Al Suwaidi', centre: 'Dubai Centre', time: '18 min ago', status: 'pending', icon: XCircle, color: 'text-red-600' },
    { id: 5, type: 'vehicle_transfer', title: 'Vehicle Transfer', customer: 'Omar Hassan', centre: 'Ajman Centre', time: '25 min ago', status: 'completed', icon: CheckCircle, color: 'text-green-600' },
  ];

  const queueStatus = [
    { centre: 'Dubai Centre', waiting: 12, inProgress: 3, avgWait: '8 min', status: 'normal' },
    { centre: 'Abu Dhabi Centre', waiting: 8, inProgress: 2, avgWait: '5 min', status: 'normal' },
    { centre: 'Sharjah Centre', waiting: 15, inProgress: 4, avgWait: '12 min', status: 'busy' },
    { centre: 'Ajman Centre', waiting: 5, inProgress: 1, avgWait: '3 min', status: 'normal' },
    { centre: 'Fujairah Centre', waiting: 20, inProgress: 5, avgWait: '18 min', status: 'critical' },
  ];

  const performanceMetrics = [
    { metric: 'First Contact Resolution', value: '94.2%', target: '90%', status: 'good' },
    { metric: 'Customer Satisfaction', value: '4.7/5.0', target: '4.5/5.0', status: 'good' },
    { metric: 'Avg Handle Time', value: '4.3 min', target: '5.0 min', status: 'good' },
    { metric: 'SLA Compliance', value: '96.8%', target: '95%', status: 'good' },
    { metric: 'Agent Utilization', value: '78%', target: '80%', status: 'warning' },
    { metric: 'Queue Abandonment', value: '3.2%', target: '<5%', status: 'good' },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('backofficeDashboard') || 'Backoffice Dashboard'}</h1>
          <p className="text-text-secondary">{t('dashboardSubtitle') || 'Real-time overview of operations and performance'}</p>
        </div>
        <div className="flex items-center gap-3">
          <button className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
            <Download className="w-4 h-4 mr-2" />
            {t('exportReport') || 'Export Report'}
          </button>
          <button className="px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
            <Plus className="w-4 h-4 mr-2" />
            {t('newTask') || 'New Task'}
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map((stat, index) => (
          <div key={index} className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all duration-300">
            <div className="flex items-center justify-between">
              <div className={`p-3 rounded-xl ${stat.color}`}>
                <stat.icon className="w-6 h-6" aria-hidden="true" />
              </div>
              <span className={`text-sm ${stat.trend === 'up' ? 'text-green-600' : 'text-red-600'} font-medium`}>{stat.change}</span>
            </div>
            <div className="mt-3">
              <p className="text-2xl font-bold text-text">{stat.value}</p>
              <p className="text-sm text-text-secondary">{stat.label}</p>
            </div>
          </div>
        ))}
      </div>

      {/* Queue Status & Performance */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Queue Status */}
        <section className="bg-surface rounded-2xl border border-border p-6">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-text">{t('queueStatus') || 'Queue Status'}</h2>
            <Link to="/backoffice/queue" className="text-sm text-primary hover:underline flex items-center gap-1">
              {t('viewAll') || 'View all'}
              <span>→</span>
            </Link>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border bg-bg">
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">{t('centre')}</th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">{t('waiting')}</th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">{t('inProgress')}</th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">{t('avgWait')}</th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">{t('status')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {queueStatus.map((q) => (
                  <tr key={q.centre} className="hover:bg-bg transition-colors">
                    <td className="px-4 py-3 font-medium text-text">{q.centre}</td>
                    <td className="px-4 py-3 text-sm text-text">{q.waiting}</td>
                    <td className="px-4 py-3 text-sm text-text">{q.inProgress}</td>
                    <td className="px-4 py-3 text-sm text-text-secondary">{q.avgWait}</td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${
                        q.status === 'normal' ? 'bg-green-50 text-green-700' :
                        q.status === 'busy' ? 'bg-yellow-50 text-yellow-700' :
                        'bg-red-50 text-red-700'
                      }`}>
                        {t(q.status) || q.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        {/* Recent Activity */}
        <section className="bg-surface rounded-2xl border border-border p-6">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-text">{t('recentActivity') || 'Recent Activity'}</h2>
            <Link to="/backoffice/transactions" className="text-sm text-primary hover:underline flex items-center gap-1">
              {t('viewAll') || 'View all'}
              <span>→</span>
            </Link>
          </div>
          <div className="space-y-3">
            {recentActivity.map((activity) => (
              <div key={activity.id} className="flex items-center justify-between p-3 bg-bg rounded-xl border border-border">
                <div className="flex items-center gap-3">
                  <div className={`p-2 rounded-lg ${activity.color} bg-current/10`}>
                    <activity.icon className="w-5 h-5" aria-hidden="true" />
                  </div>
                  <div>
                    <p className="font-medium text-text">{activity.title}</p>
                    <p className="text-sm text-text-secondary">{activity.customer} • {activity.centre}</p>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${
                    activity.status === 'completed' ? 'bg-green-50 text-green-700' :
                    activity.status === 'in_progress' ? 'bg-blue-50 text-blue-700' :
                    activity.status === 'pending' ? 'bg-yellow-50 text-yellow-700' :
                    'bg-red-50 text-red-700'
                  }`}>
                    {t(activity.status) || activity.status}
                  </span>
                  <span className="text-xs text-text-secondary">{activity.time}</span>
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>

      {/* Performance Metrics */}
      <section className="bg-surface rounded-2xl border border-border p-6">
        <h2 className="text-lg font-semibold text-text mb-4">{t('performanceMetrics') || 'Performance Metrics'}</h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {performanceMetrics.map((metric, index) => (
            <div key={index} className="p-4 bg-bg rounded-xl border border-border">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm font-medium text-text-secondary">{metric.metric}</span>
                <span className={`inline-flex items-center px-2 py-1 rounded-full text-xs font-medium ${
                  metric.status === 'good' ? 'bg-green-50 text-green-700' :
                  metric.status === 'warning' ? 'bg-yellow-50 text-yellow-700' :
                  'bg-red-50 text-red-700'
                }`}>
                  {metric.status}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <p className="text-2xl font-bold text-text">{metric.value}</p>
                <span className="text-sm text-text-secondary">Target: {metric.target}</span>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Quick Actions */}
      <section className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <Link to="/backoffice/services" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('manageServices')}</p>
              <p className="text-2xl font-bold text-text mt-1">24 {t('active')}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <FileText className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/backoffice/transactions" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('pendingTransactions')}</p>
              <p className="text-2xl font-bold text-text mt-1">47 {t('pending')}</p>
            </div>
            <div className="p-3 rounded-xl bg-yellow-50 text-yellow-600 dark:bg-yellow-900/20 dark:text-yellow-400">
              <Clock className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/backoffice/users" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('activeAgents')}</p>
              <p className="text-2xl font-bold text-text mt-1">127 {t('online')}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <Users className="w-6 h-6" />
            </div>
          </div>
        </Link>
        <Link to="/backoffice/reports" className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('generateReport')}</p>
              <p className="text-2xl font-bold text-text mt-1">{t('custom')}</p>
            </div>
            <div className="p-3 rounded-xl bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400">
              <BarChart2 className="w-6 h-6" />
            </div>
          </div>
        </Link>
      </section>
    </div>
  );
}