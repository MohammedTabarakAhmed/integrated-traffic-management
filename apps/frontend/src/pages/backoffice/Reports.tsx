import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Search, Filter, Plus, Eye, Edit, Trash2, Download, FileText, Calendar, DollarSign, TrendingUp, CheckCircle, XCircle, AlertCircle, Clock, BarChart2, PieChart, Activity, Users, Shield, Bell, Globe, MapPin, Building2, Car, FileCheck, AlertTriangle, ArrowRight, Minus, Activity as ActivityIcon, MoreVertical, ShieldCheck, ShieldOff, ShieldAlert, ShieldPlus, ShieldMinus, ShieldX, AlertOctagon, BellOff, BellPlus, BellMinus, BellRing, MessageSquare, MessageCircle, Star, Search as SearchIcon } from 'lucide-react';
import { useState } from 'react';

interface Report {
  id: string;
  name: string;
  category: string;
  schedule: string;
  lastRun: string;
  status: string;
  format: string;
  recipients: number;
}

interface Dashboard {
  id: string;
  name: string;
  category: string;
  refresh: string;
  widgets: number;
  status: string;
}

interface KPI {
  id: string;
  name: string;
  category: string;
  target: number;
  current: number;
  unit: string;
  status: string;
}

interface Execution {
  id: string;
  report: string;
  status: string;
  start: string;
  end: string;
  rows: number;
  format: string;
  size: string;
}

const reports: Report[] = [
  { id: 'RPT-001', name: 'Licences Expiring Soon', category: 'Licensing', schedule: 'Daily', lastRun: '2024-12-15 06:00', status: 'success', format: 'PDF', recipients: 12 },
  { id: 'RPT-002', name: 'Vehicle Registrations Expiring', category: 'Vehicle', schedule: 'Daily', lastRun: '2024-12-15 06:00', status: 'success', format: 'PDF', recipients: 8 },
  { id: 'RPT-003', name: 'Unpaid Violations Report', category: 'Violations', schedule: 'Weekly', lastRun: '2024-12-09 06:00', status: 'success', format: 'EXCEL', recipients: 5 },
  { id: 'RPT-004', name: 'Daily Financial Reconciliation', category: 'Finance', schedule: 'Daily', lastRun: '2024-12-15 02:00', status: 'success', format: 'EXCEL', recipients: 15 },
  { id: 'RPT-005', name: 'Monthly Accident Statistics', category: 'Accidents', schedule: 'Monthly', lastRun: '2024-12-01 06:00', status: 'success', format: 'PDF', recipients: 6 },
  { id: 'RPT-006', name: 'Service Centre Workload', category: 'Operations', schedule: 'Weekly', lastRun: '2024-12-09 06:00', status: 'success', format: 'EXCEL', recipients: 10 },
  { id: 'RPT-007', name: 'Accident Black Spots', category: 'Road Safety', schedule: 'Monthly', lastRun: '2024-12-01 06:00', status: 'success', format: 'PDF', recipients: 4 },
  { id: 'RPT-008', name: 'Compliance Audit Trail', category: 'Compliance', schedule: 'Weekly', lastRun: '2024-12-09 06:00', status: 'success', format: 'PDF', recipients: 3 },
];

const dashboards: Dashboard[] = [
  { id: 'DB-001', name: 'Executive Dashboard', category: 'Executive', refresh: '15 min', widgets: 12, status: 'active' },
  { id: 'DB-002', name: 'Licensing Dashboard', category: 'Licensing', refresh: '15 min', widgets: 8, status: 'active' },
  { id: 'DB-003', name: 'Vehicle Dashboard', category: 'Vehicle', refresh: '15 min', widgets: 10, status: 'active' },
  { id: 'DB-004', name: 'Road Safety Dashboard', category: 'Road Safety', refresh: '15 min', widgets: 8, status: 'active' },
  { id: 'DB-005', name: 'Violations Dashboard', category: 'Violations', refresh: '15 min', widgets: 9, status: 'active' },
  { id: 'DB-006', name: 'Finance Dashboard', category: 'Finance', refresh: '15 min', widgets: 11, status: 'active' },
  { id: 'DB-007', name: 'Operations Dashboard', category: 'Operations', refresh: '15 min', widgets: 10, status: 'active' },
  { id: 'DB-008', name: 'AI Dashboard', category: 'AI', refresh: '15 min', widgets: 6, status: 'active' },
];

const kpis = [
  { id: 'KPI-001', name: 'Transaction Volume', category: 'Operations', target: 10000, current: 12500, unit: 'txns/day', status: 'good' },
  { id: 'KPI-002', name: 'Avg Processing Time', category: 'Operations', target: 5, current: 3.2, unit: 'minutes', status: 'good' },
  { id: 'KPI-003', name: 'Customer Satisfaction', category: 'Quality', target: 4.5, current: 4.7, unit: '/5.0', status: 'good' },
  { id: 'KPI-004', name: 'First Contact Resolution', category: 'Quality', target: 90, current: 94.2, unit: '%', status: 'good' },
  { id: 'KPI-005', name: 'SLA Compliance', category: 'Operations', target: 95, current: 96.8, unit: '%', status: 'good' },
  { id: 'KPI-006', name: 'Agent Utilization', category: 'HR', target: 80, current: 78, unit: '%', status: 'warning' },
  { id: 'KPI-007', name: 'Queue Abandonment Rate', category: 'Operations', target: 5, current: 3.2, unit: '%', status: 'good' },
  { id: 'KPI-008', name: 'Revenue Collection', category: 'Finance', target: 100, current: 98.5, unit: '%', status: 'good' },
];

const executions = [
  { id: 'EXEC-001', report: 'Daily Financial Reconciliation', status: 'completed', start: '2024-12-15 02:00:00', end: '2024-12-15 02:03:45', rows: 15420, format: 'EXCEL', size: '2.4 MB' },
  { id: 'EXEC-002', report: 'Licences Expiring Soon', status: 'completed', start: '2024-12-15 06:00:00', end: '2024-12-15 06:00:12', rows: 1247, format: 'PDF', size: '1.8 MB' },
  { id: 'EXEC-003', report: 'Vehicle Registrations Expiring', status: 'completed', start: '2024-12-15 06:00:00', end: '2024-12-15 06:00:08', rows: 892, format: 'PDF', size: '1.2 MB' },
  { id: 'EXEC-004', report: 'Unpaid Violations Report', status: 'completed', start: '2024-12-09 06:00:00', end: '2024-12-09 06:00:45', rows: 15640, format: 'EXCEL', size: '3.1 MB' },
  { id: 'EXEC-005', report: 'Monthly Accident Statistics', status: 'completed', start: '2024-12-01 06:00:00', end: '2024-12-01 06:01:30', rows: 2341, format: 'PDF', size: '2.7 MB' },
];

const tabs = [
  { id: 'reports', label: 'Reports', icon: FileText },
  { id: 'dashboards', label: 'Dashboards', icon: BarChart2 },
  { id: 'kpis', label: 'KPIs', icon: PieChart },
  { id: 'executions', label: 'Executions', icon: Activity },
];

export function BackofficeReports() {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState('reports');
  const [search, setSearch] = useState('');

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('reportsAnalytics') || 'Reports & Analytics'}</h1>
          <p className="text-text-secondary">{t('reportsSubtitle') || 'Manage reports, dashboards, KPIs, and execution history'}</p>
        </div>
        <Link to="/backoffice/reports/create" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
          <Plus className="w-4 h-4" />
          {t('createReport') || 'Create Report'}
        </Link>
      </div>

      <div className="bg-surface rounded-2xl border border-border">
        <div className="border-b border-border">
          <nav className="flex flex-wrap -mb-px" aria-label="Reports tabs">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`px-6 py-4 text-sm font-medium border-b-2 transition-all ${
                  activeTab === tab.id
                    ? 'border-primary text-primary'
                    : 'border-transparent text-text-secondary hover:text-text hover:bg-bg'
                }`}
                role="tab"
                aria-selected={activeTab === tab.id}
              >
                <tab.icon className="w-4 h-4 inline mr-2" />
                {tab.label}
              </button>
            ))}
          </nav>
        </div>

        <div className="p-6">
          {activeTab === 'reports' && (
            <div className="space-y-6">
              <div className="flex flex-col sm:flex-row gap-4 mb-6">
                <div className="flex-1 relative">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
                  <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t('searchReports') || 'Search reports...'} className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
                </div>
                <Link to="/backoffice/reports/create" className="inline-flex items-center gap-2 px-4 py-3 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
                  <Plus className="w-4 h-4" />
                  {t('createReport')}
                </Link>
              </div>

              <div className="bg-surface rounded-2xl border border-border overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead>
                      <tr className="border-b border-border bg-bg">
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('reportCode')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('reportName')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('category')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('schedule')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('lastRun')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('status')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('format')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('recipients')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('actions')}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {reports.filter(r => r.name.toLowerCase().includes(search.toLowerCase())).map((report) => (
                        <tr key={report.id} className="hover:bg-bg transition-colors">
                          <td className="px-6 py-4"><code className="text-sm font-mono text-text">{report.id}</code></td>
                          <td className="px-6 py-4"><p className="font-medium text-text">{report.name}</p></td>
                          <td className="px-6 py-4"><span className="px-2 py-1 text-xs bg-blue-50 text-blue-700 rounded">{report.category}</span></td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{report.schedule}</td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{report.lastRun}</td>
                          <td className="px-6 py-4"><span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium bg-green-50 text-green-700">{report.status}</span></td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{report.format}</td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{report.recipients}</td>
                          <td className="px-6 py-4">
                            <div className="flex items-center gap-2">
                              <Link to={`/backoffice/reports/${report.id}`} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('view')}>
                                <Eye className="w-4 h-4" />
                              </Link>
                              <Link to={`/backoffice/reports/${report.id}/edit`} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('edit')}>
                                <Edit className="w-4 h-4" />
                              </Link>
                              <button className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('runNow')}>
                                <Activity className="w-4 h-4" />
                              </button>
                              <button className="p-2 rounded-lg hover:bg-red-50 transition-colors text-red-600" aria-label={t('delete')}>
                                <Trash2 className="w-4 h-4" />
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
          )}

          {activeTab === 'dashboards' && (
            <div className="space-y-6">
              <div className="flex flex-col sm:flex-row gap-4 mb-6">
                <div className="flex-1 relative">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
                  <input type="search" placeholder={t('searchDashboards') || 'Search dashboards...'} className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
                </div>
                <Link to="/backoffice/dashboards/create" className="inline-flex items-center gap-2 px-4 py-3 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
                  <Plus className="w-4 h-4" />
                  {t('createDashboard')}
                </Link>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {dashboards.map((dashboard) => (
                  <div key={dashboard.id} className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
                    <div className="flex items-start justify-between mb-4">
                      <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
                        <BarChart2 className="w-6 h-6" />
                      </div>
                      <span className={`inline-flex items-center px-2 py-1 rounded-full text-xs font-medium ${dashboard.status === 'active' ? 'bg-green-50 text-green-700' : 'bg-gray-50 text-gray-700'}`}>
                        {dashboard.status}
                      </span>
                    </div>
                    <h3 className="text-lg font-semibold text-text mb-1">{dashboard.name}</h3>
                    <p className="text-sm text-text-secondary mb-3">{dashboard.category}</p>
                    <div className="flex items-center justify-between text-sm text-text-secondary">
                      <span>{t('widgets') || 'Widgets'}: {dashboard.widgets}</span>
                      <span>{t('refresh') || 'Refresh'}: {dashboard.refresh}</span>
                    </div>
                    <div className="flex gap-2 mt-4">
                      <Link to={`/backoffice/dashboards/${dashboard.id}`} className="flex-1 px-3 py-2 text-sm font-medium text-primary bg-primary/10 rounded-lg hover:bg-primary/20 transition-colors text-center">{t('view')}</Link>
                      <Link to={`/backoffice/dashboards/${dashboard.id}/edit`} className="flex-1 px-3 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-lg hover:bg-surface-hover hover:border-primary/50 transition-colors text-center">{t('edit')}</Link>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {activeTab === 'kpis' && (
            <div className="space-y-6">
              <div className="flex flex-col sm:flex-row gap-4 mb-6">
                <Link to="/backoffice/kpis/create" className="inline-flex items-center gap-2 px-4 py-3 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
                  <Plus className="w-4 h-4" />
                  {t('createKPI')}
                </Link>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {kpis.map((kpi) => (
                  <div key={kpi.id} className="p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all">
                    <div className="flex items-center justify-between mb-2">
                      <span className="px-2 py-1 text-xs font-medium bg-blue-50 text-blue-700 rounded">{kpi.category}</span>
                      <span className={`inline-flex items-center px-2 py-1 rounded-full text-xs font-medium ${kpi.status === 'good' ? 'bg-green-50 text-green-700' : 'bg-yellow-50 text-yellow-700'}`}>
                        {kpi.status}
                      </span>
                    </div>
                    <h3 className="text-lg font-semibold text-text mb-1">{kpi.name}</h3>
                    <div className="flex items-center justify-between mb-3">
                      <div className="text-3xl font-bold text-text">{kpi.current.toLocaleString()}</div>
                      <div className="text-right text-sm text-text-secondary">
                        Target: {kpi.target.toLocaleString()} {kpi.unit}
                      </div>
                    </div>
                    <div className="flex items-center justify-between">
                      <div className="flex-1 bg-bg rounded-full h-2">
                        <div className="bg-primary h-2 rounded-full transition-all" style={{ width: `${Math.min((kpi.current / kpi.target) * 100, 100)}%` }} />
                      </div>
                      <span className="ml-3 text-sm font-medium text-text-secondary">{(kpi.current / kpi.target * 100).toFixed(1)}%</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {activeTab === 'executions' && (
            <div className="space-y-6">
              <div className="flex flex-col sm:flex-row gap-4 mb-6">
                <div className="flex-1 relative">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
                  <input type="search" placeholder={t('searchExecutions') || 'Search executions...'} className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
                </div>
              </div>

              <div className="bg-surface rounded-2xl border border-border overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead>
                      <tr className="border-b border-border bg-bg">
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('executionId')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('report')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('status')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('startTime')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('endTime')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('duration')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('rows')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('format')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('fileSize')}</th>
                        <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('actions')}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {executions.map((exec) => (
                        <tr key={exec.id} className="hover:bg-bg transition-colors">
                          <td className="px-6 py-4"><code className="text-sm font-mono text-text">{exec.id}</code></td>
                          <td className="px-6 py-4"><p className="font-medium text-text">{exec.report}</p></td>
                          <td className="px-6 py-4"><span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium bg-green-50 text-green-700">{exec.status}</span></td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{exec.start}</td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{exec.end}</td>
                          <td className="px-6 py-4 text-sm text-text-secondary">
                            {new Date(exec.end).getTime() - new Date(exec.start).getTime() > 0 ? 
                              Math.round((new Date(exec.end).getTime() - new Date(exec.start).getTime()) / 1000) + 's' : 
                              '-'}
                          </td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{exec.rows.toLocaleString()}</td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{exec.format}</td>
                          <td className="px-6 py-4 text-sm text-text-secondary">{exec.size}</td>
                          <td className="px-6 py-4">
                            <div className="flex items-center gap-2">
                              <button className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('view')}>
                                <Eye className="w-4 h-4" />
                              </button>
                              <a href="#" className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" download>
                                <Download className="w-4 h-4" />
                              </a>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}