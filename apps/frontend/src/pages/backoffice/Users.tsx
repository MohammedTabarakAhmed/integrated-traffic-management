import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Search, Filter, Plus, Eye, Edit, Trash2, MoreVertical, User, Shield, Key, Mail, Phone, Calendar, CheckCircle, XCircle, AlertCircle, Clock, Settings, Download, Upload, UserPlus, UserMinus, ShieldCheck, Key as KeyIcon, Bell, Globe, MapPin, Building2, Car, FileText, Star, Award, Activity, Heart, Target, Flag, Layers, Zap, Sparkles, Moon, Sun, Monitor, Palette, Database, Server, Cloud, Lock, Unlock, BarChart2, PieChart, Activity as ActivityIcon, TrendingUp, TrendingDown, Minimize2, Maximize2, Grid, List, Layout, Columns, Rows, AlignLeft, AlignCenter, AlignRight, AlignJustify, Type, Image, Video, Music, File, Folder, Home, Briefcase, CreditCard, Wallet, Gift, ShoppingBag, ShoppingCart, Package, Truck, Plane, Ship, Train, Bus, Car as CarIcon, Bike, Footprints, Loader, Users as UsersIcon, TreePine, CloudRain, CloudSnow, Anchor, Anchor as AnchorIcon } from 'lucide-react';
import { useState } from 'react';

export function BackofficeUsers() {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState('all');
  const [statusFilter, setStatusFilter] = useState('all');
  const [centreFilter, setCentreFilter] = useState('all');

  const users = [
    { id: 'USR-001', name: 'Sarah Johnson', email: 'sarah.johnson@itms.gov.ae', role: 'SERVICE_AGENT', centre: 'Dubai Centre', region: 'Dubai', status: 'active', lastLogin: '2024-12-15 10:30', permissions: ['TRANSACTION_OFFICER', 'CASHIER', 'APPOINTMENT_AGENT'], avatar: null },
    { id: 'USR-002', name: 'Mohammed Ali', email: 'mohammed.ali@itms.gov.ae', role: 'SUPERVISOR', centre: 'Abu Dhabi Centre', region: 'Abu Dhabi', status: 'active', lastLogin: '2024-12-15 10:25', permissions: ['CASE_SUPERVISOR', 'CASHIER_SUPERVISOR', 'WORKFLOW_STARTER'], avatar: null },
    { id: 'USR-003', name: 'Fatima Al Suwaidi', email: 'fatima.suwaidi@itms.gov.ae', role: 'SERVICE_AGENT', centre: 'Dubai Centre', region: 'Dubai', status: 'active', lastLogin: '2024-12-15 10:15', permissions: ['TRANSACTION_OFFICER', 'VEHICLE_OFFICER', 'PLATE_ADMIN'], avatar: null },
    { id: 'USR-004', name: 'Khalid Al Marri', email: 'khalid.marri@itms.gov.ae', role: 'SERVICE_AGENT', centre: 'Sharjah Centre', region: 'Sharjah', status: 'active', lastLogin: '2024-12-15 10:10', permissions: ['TRANSACTION_OFFICER', 'VIOLATION_OFFICER', 'CASHIER'], avatar: null },
    { id: 'USR-005', name: 'Omar Al Zaabi', email: 'omar.zaabi@itms.gov.ae', role: 'MANAGER', centre: 'Ajman Centre', region: 'Ajman', status: 'active', lastLogin: '2024-12-15 09:55', permissions: ['ACCESS_REVIEWER', 'WORKFLOW_ADMIN', 'CASE_SUPERVISOR'], avatar: null },
    { id: 'USR-006', name: 'Aisha Al Maktoum', email: 'aisha.maktoum@itms.gov.ae', role: 'SERVICE_AGENT', centre: 'Fujairah Centre', region: 'Fujairah', status: 'inactive', lastLogin: '2024-11-20 14:30', permissions: ['TRANSACTION_OFFICER', 'APPOINTMENT_AGENT'], avatar: null },
    { id: 'USR-007', name: 'Yousef Al Nuaimi', email: 'yousef.nuaimi@itms.gov.ae', role: 'SUPERVISOR', centre: 'Ras Al Khaimah Centre', region: 'Ras Al Khaimah', status: 'active', lastLogin: '2024-12-15 09:45', permissions: ['CASE_SUPERVISOR', 'WORKFLOW_STARTER', 'CASHIER_SUPERVISOR'], avatar: null },
    { id: 'USR-008', name: 'Layla Al Marzooqi', email: 'layla.marzooqi@itms.gov.ae', role: 'SERVICE_AGENT', centre: 'Umm Al Quwain Centre', region: 'Umm Al Quwain', status: 'active', lastLogin: '2024-12-15 09:30', permissions: ['TRANSACTION_OFFICER', 'CASHIER', 'DOCUMENT_OFFICER'], avatar: null },
    { id: 'USR-009', name: 'Abdullah Al Falasi', email: 'abdullah.falasi@itms.gov.ae', role: 'SERVICE_AGENT', centre: 'Dubai Centre', region: 'Dubai', status: 'suspended', lastLogin: '2024-11-15 16:20', permissions: ['TRANSACTION_OFFICER', 'VIOLATION_OFFICER'], avatar: null },
    { id: 'USR-010', name: 'Noura Al Qasimi', email: 'noura.qasimi@itms.gov.ae', role: 'AUDITOR', centre: 'Head Office', region: 'Dubai', status: 'active', lastLogin: '2024-12-15 09:15', permissions: ['AUDITOR', 'ACCESS_REVIEWER'], avatar: null },
  ];

  const roles = ['all', 'SERVICE_AGENT', 'SUPERVISOR', 'MANAGER', 'AUDITOR', 'ADMIN'];
  const statuses = ['all', 'active', 'inactive', 'suspended'];
  const centres = ['all', 'Dubai Centre', 'Abu Dhabi Centre', 'Sharjah Centre', 'Ajman Centre', 'Fujairah Centre', 'Ras Al Khaimah Centre', 'Umm Al Quwain Centre', 'Head Office'];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('manageUsers') || 'Manage Users'}</h1>
          <p className="text-text-secondary">{t('usersSubtitle') || 'Manage user accounts, roles, and permissions'}</p>
        </div>
        <Link to="/backoffice/users/create" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
          <UserPlus className="w-4 h-4" />
          {t('createUser') || 'Create User'}
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
            placeholder={t('searchUsers') || 'Search users...'}
            className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
          />
        </div>
        <select value={roleFilter} onChange={(e) => setRoleFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByRole')}>
          {roles.map(role => <option key={role} value={role}>{role === 'all' ? t('allRoles') : t(role)}</option>)}
        </select>
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByStatus')}>
          {statuses.map(st => <option key={st} value={st}>{st === 'all' ? t('allStatus') : t(st)}</option>)}
        </select>
        <select value={centreFilter} onChange={(e) => setCentreFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByCentre')}>
          {centres.map(c => <option key={c} value={c}>{c === 'all' ? t('allCentres') : t(c)}</option>)}
        </select>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4 mb-6">
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('totalUsers')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.length}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <User className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('activeUsers')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.filter(u => u.status === 'active').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <CheckCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('inactiveUsers')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.filter(u => u.status === 'inactive').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-gray-50 text-gray-600 dark:bg-gray-900/20 dark:text-gray-400">
              <XCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('suspendedUsers')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.filter(u => u.status === 'suspended').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400">
              <AlertCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('agentsOnline')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.filter(u => u.status === 'active').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <ActivityIcon className="w-6 h-6" />
            </div>
          </div>
        </div>
      </div>

      {/* Users Table */}
      <div className="bg-surface rounded-2xl border border-border overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border bg-bg">
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('user')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('role')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('centre')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('region')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('status')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('lastLogin')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('permissions')}</th>
                <th className="px-6 py-4 text-left text-sm font-semibold text-text-secondary">{t('actions')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {users.filter(u => 
                (roleFilter === 'all' || u.role === roleFilter) &&
                (statusFilter === 'all' || u.status === statusFilter) &&
                (centreFilter === 'all' || u.centre === centreFilter) &&
                (u.name.toLowerCase().includes(search.toLowerCase()) || u.email.toLowerCase().includes(search.toLowerCase()) || u.id.toLowerCase().includes(search.toLowerCase()))
              ).map((user) => (
                <tr key={user.id} className="hover:bg-bg transition-colors">
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                        <User className="w-5 h-5 text-primary" />
                      </div>
                      <div>
                        <p className="font-medium text-text">{user.name}</p>
                        <p className="text-sm text-text-secondary">{user.email}</p>
                        <p className="text-xs text-text-secondary font-mono">{user.id}</p>
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-400">
                      {user.role.replace('_', ' ')}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm text-text">{user.centre}</td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{user.region}</td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium ${
                      user.status === 'active' ? 'bg-green-50 text-green-700' :
                      user.status === 'inactive' ? 'bg-gray-50 text-gray-700' :
                      'bg-red-50 text-red-700'
                    }`}>
                      {user.status}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm text-text-secondary">{user.lastLogin}</td>
                  <td className="px-6 py-4">
                    <div className="flex flex-wrap gap-1">
                      {user.permissions.slice(0, 3).map((perm, i) => (
                        <span key={i} className="px-2 py-0.5 text-xs bg-bg rounded border border-border text-text-secondary">{perm}</span>
                      ))}
                      {user.permissions.length > 3 && (
                        <span className="px-2 py-0.5 text-xs bg-primary/10 text-primary rounded border border-primary/20">+{user.permissions.length - 3}</span>
                      )}
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <Link to={`/backoffice/users/${user.id}`} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('view')}>
                        <Eye className="w-4 h-4" />
                      </Link>
                      <Link to={`/backoffice/users/${user.id}/edit`} className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('edit')}>
                        <Edit className="w-4 h-4" />
                      </Link>
                      <button className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('permissions')}>
                        <ShieldCheck className="w-4 h-4" />
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
              <p className="text-sm text-text-secondary">{t('totalUsers')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.length}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <User className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('activeAgents')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.filter(u => u.status === 'active' && u.role === 'SERVICE_AGENT').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <User className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('supervisors')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.filter(u => u.role === 'SUPERVISOR').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400">
              <Shield className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('managers')}</p>
              <p className="text-2xl font-bold text-text mt-1">{users.filter(u => u.role === 'MANAGER').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-orange-50 text-orange-600 dark:bg-orange-900/20 dark:text-orange-400">
              <UserPlus className="w-6 h-6" />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}