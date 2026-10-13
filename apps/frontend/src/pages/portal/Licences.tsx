import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { FileText, Plus, Search, Filter, Clock, CheckCircle, AlertCircle, XCircle, Download, Eye, Edit, Trash2, Shield } from 'lucide-react';

export function PortalLicences() {
  const { t } = useTranslation();

  const licences = [
    { id: 1, number: 'DL-2024-001234', class: 'Class 3 (Light Vehicle)', issueDate: '2022-01-15', expiryDate: '2032-01-14', status: 'active', medicalExpiry: '2025-01-14', eyeTestExpiry: '2024-12-15' },
    { id: 2, number: 'DL-2024-001235', class: 'Class 1 (Motorcycle)', issueDate: '2020-06-20', expiryDate: '2030-06-19', status: 'active', medicalExpiry: '2025-06-19', eyeTestExpiry: '2024-11-20' },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('myLicences') || 'My Licences'}</h1>
          <p className="text-text-secondary">{t('licencesSubtitle') || 'Manage your driving licences'}</p>
        </div>
        <Link to="/portal/services/licences/issue" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
          <Plus className="w-4 h-4" />
          {t('applyNewLicence') || 'Apply for New Licence'}
        </Link>
      </div>

      <div className="grid grid-cols-1 gap-4">
        {licences.map((licence) => (
          <Link key={licence.id} to={`/portal/licences/${licence.id}`} className="group p-6 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all duration-300">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <div className="flex items-center gap-4">
                <div className="p-3 rounded-xl bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400">
                  <FileText className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-text group-hover:text-primary transition-colors">{licence.number}</h3>
                  <p className="text-text-secondary">{licence.class}</p>
                </div>
              </div>

              <div className="flex flex-col sm:flex-row sm:items-center gap-4">
                <div className="grid grid-cols-3 gap-4 text-center sm:grid-cols-3">
                  <div className="p-3 bg-bg rounded-xl">
                    <p className="text-2xl font-bold text-text">{licence.status === 'active' ? t('active') : t('expired')}</p>
                    <p className="text-xs text-text-secondary">{t('status')}</p>
                  </div>
                  <div className="p-3 bg-bg rounded-xl">
                    <p className="text-sm font-medium text-text">Expires</p>
                    <p className="text-lg font-bold text-text">{new Date(licence.expiryDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}</p>
                  </div>
                  <div className="p-3 bg-bg rounded-xl">
                    <p className="text-sm font-medium text-text">Issued</p>
                    <p className="text-lg font-bold text-text">{new Date(licence.issueDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}</p>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <Link to={`/portal/licences/${licence.id}/renew`} className="px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
                    {t('renew') || 'Renew'}
                  </Link>
                  <button className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
                    {t('viewDetails') || 'Details'}
                  </button>
                </div>
              </div>
            </div>

            {/* Additional Info */}
            <div className="pt-4 border-t border-border hidden sm:block">
              <div className="grid grid-cols-3 gap-4 text-sm">
                <div>
                  <p className="text-text-secondary">{t('medicalCertificate') || 'Medical Certificate'}</p>
                  <p className="font-medium text-text">{new Date(licence.medicalExpiry).toLocaleDateString()}</p>
                </div>
                <div>
                  <p className="text-text-secondary">{t('eyeTest') || 'Eye Test'}</p>
                  <p className="font-medium text-text">{new Date(licence.eyeTestExpiry).toLocaleDateString()}</p>
                </div>
                <div>
                  <p className="text-text-secondary">{t('daysUntilExpiry') || 'Days Until Expiry'}</p>
                  <p className="font-medium text-text">{Math.ceil((new Date(licence.expiryDate).getTime() - Date.now()) / (1000 * 60 * 60 * 24))} {t('days')}</p>
                </div>
              </div>
            </div>
          </Link>
        ))}
      </div>

      {/* CTA */}
      <div className="text-center p-8 bg-primary/5 rounded-2xl border border-primary/20">
        <h3 className="text-xl font-semibold text-primary mb-2">{t('needNewLicence') || 'Need a New Licence?'}</h3>
        <p className="text-primary/80 mb-4">{t('needNewLicenceDesc') || 'Apply for a new driving licence or add a vehicle class to your existing licence.'}</p>
        <Link to="/portal/services/licences/issue" className="inline-flex items-center gap-2 px-6 py-3 text-base font-semibold text-primary bg-white rounded-xl hover:bg-primary/10 transition-colors">
          {t('applyNow') || 'Apply Now'}
          <Plus className="w-4 h-4" />
        </Link>
      </div>
    </div>
  );
}