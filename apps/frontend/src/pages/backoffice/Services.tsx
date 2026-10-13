import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Plus, Search, Filter, Edit, Trash2, Eye, Download, Upload, FileText, Car, Shield, AlertTriangle, CreditCard, Calendar, Building2, User, Settings, MoreVertical, CheckCircle, XCircle, AlertCircle, Clock, Filter as FilterIcon, Globe } from 'lucide-react';
import { useState } from 'react';

export function BackofficeServices() {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [statusFilter, setStatusFilter] = useState('all');

  const services = [
    { id: 1, code: 'VEH_REG_NEW', name: 'New Vehicle Registration', category: 'Vehicles', status: 'active', channels: ['PORTAL', 'BACKOFFICE', 'KIOSK'], sla: '30 min', region: 'ALL', fee: 'AED 450', version: 3 },
    { id: 2, code: 'VEH_REG_RENEW', name: 'Vehicle Registration Renewal', category: 'Vehicles', status: 'active', channels: ['PORTAL', 'BACKOFFICE', 'APP'], sla: '15 min', region: 'ALL', fee: 'AED 350', version: 5 },
    { id: 3, code: 'VEH_TRANSFER', name: 'Vehicle Ownership Transfer', category: 'Vehicles', status: 'active', channels: ['BACKOFFICE'], sla: '45 min', region: 'ALL', fee: 'AED 350', version: 2 },
    { id: 4, code: 'LIC_NEW', name: 'New Driving Licence Issue', category: 'Licences', status: 'active', channels: ['BACKOFFICE'], sla: '60 min', region: 'ALL', fee: 'AED 600', version: 4 },
    { id: 5, code: 'LIC_RENEW', name: 'Driving Licence Renewal', category: 'Licences', status: 'active', channels: ['PORTAL', 'BACKOFFICE', 'APP'], sla: '20 min', region: 'ALL', fee: 'AED 300', version: 6 },
    { id: 6, code: 'VIO_PAY', name: 'Violation Payment', category: 'Violations', status: 'active', channels: ['PORTAL', 'BACKOFFICE', 'APP', 'KIOSK'], sla: '5 min', region: 'ALL', fee: 'Variable', version: 3 },
    { id: 7, code: 'VIO_OBJ', name: 'Violation Objection', category: 'Violations', status: 'active', channels: ['PORTAL', 'BACKOFFICE'], sla: '30 min', region: 'ALL', fee: 'AED 50', version: 2 },
    { id: 8, code: 'PLATE_NEW', name: 'New Plate Registration', category: 'Plates', status: 'active', channels: ['BACKOFFICE', 'KIOSK'], sla: '30 min', region: 'ALL', fee: 'AED 200', version: 1 },
    { id: 9, code: 'PLATE_AUCTION', name: 'Premium Plate Auction', category: 'Plates', status: 'active', channels: ['PORTAL', 'BACKOFFICE'], sla: 'N/A', region: 'DUBAI', fee: 'Auction', version: 1 },
    { id: 10, code: 'APPT_BOOK', name: 'Appointment Booking', category: 'Appointments', status: 'active', channels: ['PORTAL', 'BACKOFFICE', 'APP', 'CALLCENTRE'], sla: '10 min', region: 'ALL', fee: 'Free', version: 4 },
  ];

  const categories = ['all', 'Vehicles', 'Licences', 'Violations', 'Plates', 'Appointments', 'Payments', 'Documents', 'Permits'];
  const statuses = ['all', 'active', 'inactive', 'draft', 'archived'];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('manageServices') || 'Manage Services'}</h1>
          <p className="text-text-secondary">{t('servicesSubtitle') || 'Manage service catalogue, availability rules, and configurations'}</p>
        </div>
        <Link to="/backoffice/services/create" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
          <Plus className="w-4 h-4" />
          {t('createService') || 'Create Service'}
        </Link>
      </div>

      {/* Filters */}
      <div className="flex flex-col sm:flex-row gap-4 mb-6">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('searchServices') || 'Search services...'}
            className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
          />
        </div>
        <select value={categoryFilter} onChange={(e) => setCategoryFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByCategory')}>
          {categories.map(cat => <option key={cat} value={cat}>{cat === 'all' ? t('allCategories') : t(cat)}</option>)}
        </select>
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByStatus')}>
          {statuses.map(st => <option key={st} value={st}>{st === 'all' ? t('allStatus') : t(st)}</option>)}
        </select>
      </div>

      {/* Services Table */}
      <div className="bg-surface rounded-2xl border border-border overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border bg-bg">
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('serviceCode')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('serviceName')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('category')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('status')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('channels')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('sla')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('region')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('fee')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('version')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('actions')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {services.filter(s => 
                (categoryFilter === 'all' || s.category === categoryFilter) &&
                (statusFilter === 'all' || s.status === statusFilter) &&
                (s.code.toLowerCase().includes(search.toLowerCase()) || s.name.toLowerCase().includes(search.toLowerCase()))
              ).map((service) => (
                <tr key={service.id} className="hover:bg-bg transition-colors">
                  <td className="px-6 py-4">
                    <code className="text-sm font-mono text-text">{service.code}</code>
                  </td>
                  <td className="px-6 py-4">
                    <p className="font-medium text-text">{service.name}</p>
                    <p className="text-xs text-text-secondary">v{service.version}</p>
                  </td>
                  <td className="px-6 py-4">
                    <span className="inline-flex items-center px-2 py-1 rounded-full text-xs font-medium bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-400">
                      {service.category}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${
                      service.status === 'active' ? 'bg-green-50 text-green-700' :
                      service.status === 'inactive' ? 'bg-gray-50 text-gray-700' :
                      service.status === 'draft' ? 'bg-yellow-50 text-yellow-700' :
                      'bg-purple-50 text-purple-700'
                    }`}>
                      {service.status}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex flex-wrap gap-1">
                      {service.channels.map((ch, i) => (
                        <span key={i} className="px-2 py-0.5 text-xs bg-bg rounded border border-border text-text-secondary">{ch}</span>
                      ))}
                    </div>
                  </td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{service.sla}</td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{service.region}</td>
                  <td className="px-6 py-4 text-sm font-mono text-text">{service.fee}</td>
                  <td className="px-6 py-4 text-sm text-text-secondary">v{service.version}</td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <Link to={`/backoffice/services/${service.id}`} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('view')}>
                        <Eye className="w-4 h-4" />
                      </Link>
                      <Link to={`/backoffice/services/${service.id}/edit`} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('edit')}>
                        <Edit className="w-4 h-4" />
                      </Link>
                      <button className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('availability')}>
                        <Settings className="w-4 h-4" />
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

      {/* Stats Summary */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('totalServices')}</p>
              <p className="text-2xl font-bold text-text mt-1">{services.length}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <FileText className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('activeServices')}</p>
              <p className="text-2xl font-bold text-text mt-1">{services.filter(s => s.status === 'active').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <CheckCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('portalEnabled')}</p>
              <p className="text-2xl font-bold text-text mt-1">{services.filter(s => s.channels.includes('PORTAL')).length}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <Globe className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('backofficeOnly')}</p>
              <p className="text-2xl font-bold text-text mt-1">{services.filter(s => !s.channels.includes('PORTAL')).length}</p>
            </div>
            <div className="p-3 rounded-xl bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400">
              <Building2 className="w-6 h-6" />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}