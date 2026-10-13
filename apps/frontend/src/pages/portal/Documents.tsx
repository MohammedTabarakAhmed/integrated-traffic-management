import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { FileText, FileImage, FileText as FilePdf, FileSpreadsheet, Upload, Download, Search, Filter, Plus, Eye, MoreVertical, Trash2, CheckCircle, AlertCircle, XCircle, Clock, Shield, Star, FolderOpen, FolderPlus } from 'lucide-react';
import { useState } from 'react';

export function PortalDocuments() {
  const { t } = useTranslation();
  const [filter, setFilter] = useState('all');

  const documents = [
    { id: 1, name: 'Vehicle Registration Card', type: 'pdf', category: 'Vehicle', service: 'Registration Renewal', size: '245 KB', date: '2024-11-20', status: 'valid', verified: true },
    { id: 2, name: 'Driving Licence', type: 'pdf', category: 'Licence', service: 'Licence Renewal', size: '180 KB', date: '2024-11-15', status: 'valid', verified: true },
    { id: 3, name: 'Insurance Certificate', type: 'pdf', category: 'Vehicle', service: 'Registration Renewal', size: '310 KB', date: '2024-11-10', status: 'expiring_soon', verified: true },
    { id: 4, name: 'Emirates ID', type: 'jpg', category: 'Identity', service: 'Profile', size: '1.2 MB', date: '2024-11-05', status: 'valid', verified: true },
    { id: 5, name: 'Violation Receipt', type: 'pdf', category: 'Violation', service: 'Fine Payment', size: '85 KB', date: '2024-10-22', status: 'valid', verified: true },
    { id: 6, name: 'Medical Certificate', type: 'pdf', category: 'Licence', service: 'Licence Renewal', size: '420 KB', date: '2024-10-15', status: 'valid', verified: false },
    { id: 7, name: 'Eye Test Certificate', type: 'pdf', category: 'Licence', service: 'Licence Renewal', size: '120 KB', date: '2024-10-10', status: 'valid', verified: true },
    { id: 8, name: 'Vehicle Inspection Report', type: 'pdf', category: 'Vehicle', service: 'Inspection', size: '560 KB', date: '2024-09-28', status: 'valid', verified: true },
  ];

  const categories = ['all', 'Vehicle', 'Licence', 'Violation', 'Identity', 'Other'];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('myDocuments') || 'My Documents'}</h1>
          <p className="text-text-secondary">{t('documentsSubtitle') || 'Manage and verify your documents'}</p>
        </div>
        <div className="flex items-center gap-3">
          <Link to="/portal/services/documents/upload" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
            <Upload className="w-4 h-4" />
            {t('uploadDocument') || 'Upload Document'}
          </Link>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('totalDocuments') || 'Total Documents'}</p>
              <p className="text-2xl font-bold text-text mt-1">{documents.length}</p>
            </div>
            <div className="p-3 rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400">
              <FileText className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('verifiedDocuments') || 'Verified'}</p>
              <p className="text-2xl font-bold text-text mt-1">{documents.filter(d => d.verified).length}</p>
            </div>
            <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
              <CheckCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('expiringSoon') || 'Expiring Soon'}</p>
              <p className="text-2xl font-bold text-text mt-1">{documents.filter(d => d.status === 'expiring_soon').length}</p>
            </div>
            <div className="p-3 rounded-xl bg-orange-50 text-orange-600 dark:bg-orange-900/20 dark:text-orange-400">
              <AlertCircle className="w-6 h-6" />
            </div>
          </div>
        </div>
        <div className="p-5 bg-surface rounded-2xl border border-border">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-text-secondary">{t('pendingVerification') || 'Pending Verification'}</p>
              <p className="text-2xl font-bold text-text mt-1">{documents.filter(d => !d.verified).length}</p>
            </div>
            <div className="p-3 rounded-xl bg-yellow-50 text-yellow-600 dark:bg-yellow-900/20 dark:text-yellow-400">
              <Clock className="w-6 h-6" />
            </div>
          </div>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-col sm:flex-row gap-4 mb-6">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
          <input type="search" placeholder={t('searchDocuments') || 'Search documents...'} className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
        </div>
        <select value={filter} onChange={(e) => setFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByCategory')}>
          {categories.map(cat => <option key={cat} value={cat}>{cat === 'all' ? t('allCategories') : t(cat)}</option>)}
        </select>
      </div>

      {/* Documents Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        {documents.filter(d => filter === 'all' || d.category === filter).map((doc) => (
          <Link key={doc.id} to={`/portal/documents/${doc.id}`} className="group p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all duration-300">
            <div className="flex items-start justify-between mb-4">
              <div className={`p-3 rounded-xl ${getTypeColor(doc.type)}`}>
                {getTypeIcon(doc.type)}
              </div>
              <span className={`inline-flex items-center px-2 py-1 rounded-full text-xs font-medium ${
                doc.status === 'valid' ? 'bg-green-50 text-green-700' :
                doc.status === 'expiring_soon' ? 'bg-orange-50 text-orange-700' :
                'bg-red-50 text-red-700'
              }`}>
                {t(doc.status) || doc.status}
              </span>
            </div>

            <div className="mb-4">
              <h3 className="font-semibold text-text group-hover:text-primary transition-colors line-clamp-1">{doc.name}</h3>
              <p className="text-sm text-text-secondary mt-1">{doc.category} • {doc.service}</p>
            </div>

            <div className="space-y-2 text-sm text-text-secondary">
              <div className="flex items-center gap-2">
                <FileText className="w-4 h-4" />
                <span>{doc.size}</span>
              </div>
              <div className="flex items-center gap-2">
                <Calendar className="w-4 h-4" />
                <span>{new Date(doc.date).toLocaleDateString()}</span>
              </div>
              <div className="flex items-center gap-2">
                {doc.verified ? (
                  <span className="inline-flex items-center gap-1 text-green-600 dark:text-green-400">
                    <CheckCircle className="w-4 h-4" />
                    {t('verified') || 'Verified'}
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 text-yellow-600 dark:text-yellow-400">
                    <Clock className="w-4 h-4" />
                    {t('pendingVerification') || 'Pending Verification'}
                  </span>
                )}
              </div>
            </div>

            <div className="pt-4 border-t border-border flex items-center justify-between">
              <span className="text-xs text-text-secondary">{t('uploaded') || 'Uploaded'} {new Date(doc.date).toLocaleDateString()}</span>
              <div className="flex items-center gap-2">
                <button className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('view')}>
                  <Eye className="w-4 h-4" />
                </button>
                <button className="p-2 rounded-lg hover:bg-surface-hover transition-colors text-text-secondary" aria-label={t('download')}>
                  <Download className="w-4 h-4" />
                </button>
              </div>
            </div>
          </Link>
        ))}
      </div>

      {/* Upload CTA */}
      <div className="text-center p-8 bg-primary/5 rounded-2xl border border-primary/20">
        <h3 className="text-xl font-semibold text-primary mb-2">{t('needToUpload') || 'Need to Upload a Document?'}</h3>
        <p className="text-primary/80 mb-4">{t('needToUploadDesc') || 'Upload required documents for your services securely.'}</p>
        <Link to="/portal/services/documents/upload" className="inline-flex items-center gap-2 px-6 py-3 text-base font-semibold text-primary bg-white rounded-xl hover:bg-primary/10 transition-colors">
          <Upload className="w-4 h-4" />
          {t('uploadDocument') || 'Upload Document'}
        </Link>
      </div>
    </div>
  );
}

function getTypeIcon(type: string) {
  switch (type) {
    case 'pdf': return <FilePdf className="w-6 h-6" />;
    case 'jpg':
    case 'png':
    case 'jpeg': return <FileImage className="w-6 h-6" />;
    case 'xls':
    case 'xlsx': return <FileSpreadsheet className="w-6 h-6" />;
    default: return <FileText className="w-6 h-6" />;
  }
}

function getTypeColor(type: string) {
  switch (type) {
    case 'pdf': return 'bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400';
    case 'jpg':
    case 'png':
    case 'jpeg': return 'bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400';
    case 'xls':
    case 'xlsx': return 'bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400';
    default: return 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400';
  }
}

import { Calendar } from 'lucide-react';