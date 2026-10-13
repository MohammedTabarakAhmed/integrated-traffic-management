import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { User, Mail, Phone, MapPin, Calendar, Shield, Lock, Key, Bell, Globe, Save, Loader2, Eye, EyeOff, Camera, X, CheckCircle, Edit } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';

interface FormData {
  fullNameEn: string;
  fullNameAr: string;
  email: string;
  mobile: string;
  nationality: string;
  birthDate: string;
  gender: 'MALE' | 'FEMALE';
  address: string;
  emergencyContact: string;
  emergencyPhone: string;
  language: 'en' | 'ar';
  emailNotifications: boolean;
  smsNotifications: boolean;
  pushNotifications: boolean;
  twoFactorEnabled: boolean;
  theme?: 'light' | 'dark' | 'system';
}

export function PortalProfile() {
  const { t } = useTranslation();
  const { user, setLanguage } = useAuth();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('personal');
  const [isEditing, setIsEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [successMessage, setSuccessMessage] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [formData, setFormData] = useState<FormData>({
    fullNameEn: user?.fullName || '',
    fullNameAr: user?.fullNameAr || '',
    email: user?.email || '',
    mobile: '',
    nationality: 'ARE',
    birthDate: '',
    gender: 'MALE',
    address: '',
    emergencyContact: '',
    emergencyPhone: '',
    language: user?.language || 'en',
    emailNotifications: true,
    smsNotifications: true,
    pushNotifications: true,
    twoFactorEnabled: false,
  });

  const tabs = [
    { id: 'personal', label: t('personalInfo') || 'Personal Info', icon: User },
    { id: 'contact', label: t('contactInfo') || 'Contact', icon: Mail },
    { id: 'security', label: t('security') || 'Security', icon: Shield },
    { id: 'preferences', label: t('preferences') || 'Preferences', icon: Globe },
  ];

  const handleSave = async () => {
    setSaving(true);
    setSuccessMessage('');
    try {
      await new Promise(resolve => setTimeout(resolve, 1000));
      setSuccessMessage(t('savedSuccessfully') || 'Changes saved successfully');
      setIsEditing(false);
    } catch (error) {
      setSuccessMessage(t('saveFailed') || 'Failed to save changes');
    } finally {
      setSaving(false);
    }
  };

  const handleChange = (field: keyof FormData, value: any) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('myProfile') || 'My Profile'}</h1>
          <p className="text-text-secondary">{t('profileSubtitle') || 'Manage your personal information and settings'}</p>
        </div>
      </div>

      {successMessage && (
        <div className="p-4 bg-green-50 border border-green-200 dark:bg-green-900/20 dark:border-green-800 rounded-xl flex items-center gap-3 text-green-700 dark:text-green-400" role="alert">
          <CheckCircle className="w-5 h-5" />
          <span>{successMessage}</span>
        </div>
      )}

      {/* Profile Header */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <div className="flex flex-col sm:flex-row sm:items-center gap-6">
          <div className="relative">
            <div className="w-24 h-24 rounded-full bg-primary/10 flex items-center justify-center">
              <User className="w-12 h-12 text-primary" />
            </div>
            {isEditing && (
              <button className="absolute bottom-0 right-0 p-2 bg-primary text-white rounded-full hover:bg-primary-dark transition-colors" aria-label={t('changePhoto')}>
                <Camera className="w-4 h-4" />
              </button>
            )}
          </div>
          <div className="flex-1">
            <h2 className="text-xl font-bold text-text">{formData.fullNameEn || user?.fullName || t('guest')}</h2>
            <p className="text-text-secondary">{user?.email}</p>
            <div className="flex items-center gap-4 mt-3">
              <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-primary/10 text-primary">
                {user?.roles?.includes('BACKOFFICE') ? t('backofficeAgent') : t('customer')}
              </span>
              {user?.language === 'ar' && (
                <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-green-50 text-green-700">
                  <Globe className="w-3 h-3 mr-1" />
                  {t('arabic')}
                </span>
              )}
            </div>
          </div>
          {isEditing ? (
            <button onClick={handleSave} disabled={saving} className="px-6 py-3 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors disabled:opacity-50 flex items-center gap-2">
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
              {saving ? t('saving') : t('saveChanges')}
            </button>
          ) : (
            <button onClick={() => setIsEditing(true)} className="px-6 py-3 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
              <Edit className="w-4 h-4 mr-2" />
              {t('editProfile')}
            </button>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="bg-surface rounded-2xl border border-border">
        <div className="border-b border-border">
          <nav className="flex flex-wrap -mb-px" aria-label="Profile tabs">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => { setActiveTab(tab.id); setIsEditing(false); }}
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
          {/* Personal Info Tab */}
          {activeTab === 'personal' && (
            <form className="space-y-6" onSubmit={(e) => { e.preventDefault(); handleSave(); }}>
              <h3 className="text-lg font-semibold text-text">{t('personalInformation')}</h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('fullNameEn')} <span className="text-error">*</span></label>
                  <input value={formData.fullNameEn} onChange={(e) => handleChange('fullNameEn', e.target.value)} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all" disabled={!isEditing} />
                </div>
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('fullNameAr')}</label>
                  <input value={formData.fullNameAr} onChange={(e) => handleChange('fullNameAr', e.target.value)} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all" disabled={!isEditing} dir="rtl" />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('nationality')}</label>
                  <select value={formData.nationality} onChange={(e) => handleChange('nationality', e.target.value)} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing}>
                    <option value="ARE">United Arab Emirates</option>
                    <option value="SAU">Saudi Arabia</option>
                    <option value="OMN">Oman</option>
                    <option value="QAT">Qatar</option>
                    <option value="BHR">Bahrain</option>
                    <option value="KWT">Kuwait</option>
                    <option value="OTHER">Other</option>
                  </select>
                </div>
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('birthDate')}</label>
                  <input type="date" value={formData.birthDate} onChange={(e) => handleChange('birthDate', e.target.value)} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing} />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('gender')}</label>
                  <select value={formData.gender} onChange={(e) => handleChange('gender', e.target.value)} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing}>
                    <option value="MALE">{t('male')}</option>
                    <option value="FEMALE">{t('female')}</option>
                  </select>
                </div>
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('address')}</label>
                  <textarea value={formData.address} onChange={(e) => handleChange('address', e.target.value)} rows={3} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing} />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('emergencyContact')}</label>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <input placeholder={t('emergencyContactName')} value={formData.emergencyContact} onChange={(e) => handleChange('emergencyContact', e.target.value)} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing} />
                  <input type="tel" placeholder={t('emergencyContactPhone')} value={formData.emergencyPhone} onChange={(e) => handleChange('emergencyPhone', e.target.value)} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing} />
                </div>
              </div>
            </form>
          )}

          {/* Contact Tab */}
          {activeTab === 'contact' && (
            <form className="space-y-6" onSubmit={(e) => { e.preventDefault(); handleSave(); }}>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('email')} <span className="text-error">*</span></label>
                  <input type="email" value={formData.email} onChange={(e) => handleChange('email', e.target.value)} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing} />
                </div>
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('mobile')} <span className="text-error">*</span></label>
                  <input type="tel" value={formData.mobile} onChange={(e) => handleChange('mobile', e.target.value.replace(/\D/g, '').slice(0, 10))} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing} />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('preferredLanguage')}</label>
                <div className="flex gap-4">
                  {(['en', 'ar'] as const).map(lang => (
                    <button key={lang} type="button" onClick={() => { handleChange('language', lang); setLanguage(lang); }} className={`flex-1 px-4 py-3 rounded-xl border-2 font-medium transition-all ${formData.language === lang ? 'border-primary bg-primary/10 text-primary' : 'border-border text-text-secondary hover:border-primary/50'}`}>
                      {lang === 'en' ? 'English' : 'العربية'}
                    </button>
                  ))}
                </div>
              </div>
            </form>
          )}

          {/* Security Tab */}
          {activeTab === 'security' && (
            <div className="space-y-6">
              <div className="p-4 bg-surface border border-border rounded-xl">
                <div className="flex items-center justify-between">
                  <div>
                    <h4 className="font-medium text-text">{t('twoFactorAuth') || 'Two-Factor Authentication'}</h4>
                    <p className="text-sm text-text-secondary">{t('twoFactorDesc') || 'Add an extra layer of security to your account'}</p>
                  </div>
                  <button className={`px-4 py-2 rounded-lg font-medium transition-all ${formData.twoFactorEnabled ? 'bg-primary text-white' : 'bg-surface border border-border text-text-secondary hover:bg-surface-hover'}`} onClick={() => handleChange('twoFactorEnabled', !formData.twoFactorEnabled)} disabled={!isEditing}>
                    {formData.twoFactorEnabled ? t('enabled') : t('enable')}
                  </button>
                </div>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <div className="flex items-center justify-between">
                  <div>
                    <h4 className="font-medium text-text">{t('changePassword') || 'Change Password'}</h4>
                    <p className="text-sm text-text-secondary">{t('changePasswordDesc') || 'Update your password regularly for security'}</p>
                  </div>
                  <button className="px-4 py-2 text-sm font-medium text-primary bg-primary/10 rounded-lg hover:bg-primary/20 transition-colors" disabled={!isEditing}>
                    {t('changePassword')}
                  </button>
                </div>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-3">{t('activeSessions') || 'Active Sessions'}</h4>
                <div className="space-y-2">
                  <div className="flex items-center justify-between p-3 bg-surface rounded-lg border border-border">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center"><Shield className="w-5 h-5 text-primary" /></div>
                      <div>
                        <p className="font-medium text-text">{t('currentSession') || 'Current Session'}</p>
                        <p className="text-sm text-text-secondary">{t('thisDevice') || 'This device'}</p>
                      </div>
                    </div>
                    <span className="px-2 py-1 text-xs bg-green-50 text-green-700 rounded-full">{t('active')}</span>
                  </div>
                  <div className="flex items-center justify-between p-3 bg-surface rounded-lg border border-border">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-full bg-gray-200/50 flex items-center justify-center"><Lock className="w-5 h-5 text-text-muted" /></div>
                      <div>
                        <p className="font-medium text-text">Mobile App</p>
                        <p className="text-sm text-text-secondary">{t('lastActive') || 'Last active 2 hours ago'}</p>
                      </div>
                    </div>
                    <button className="px-3 py-1 text-xs text-red-600 hover:text-red-700">{t('revoke')}</button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Preferences Tab */}
          {activeTab === 'preferences' && (
            <form className="space-y-6" onSubmit={(e) => { e.preventDefault(); handleSave(); }}>
              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-3">{t('notificationPreferences')}</h4>
                <div className="space-y-3">
                  {[
                    { key: 'emailNotifications' as const, label: t('emailNotifications'), desc: t('emailNotificationsDesc') },
                    { key: 'smsNotifications' as const, label: t('smsNotifications'), desc: t('smsNotificationsDesc') },
                    { key: 'pushNotifications' as const, label: t('pushNotifications'), desc: t('pushNotificationsDesc') },
                  ].map(item => (
                    <label key={item.key} className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                      <div className="flex items-center gap-3">
                        <input type="checkbox" checked={formData[item.key]} onChange={(e) => handleChange(item.key, e.target.checked)} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" disabled={!isEditing} />
                        <div>
                          <p className="font-medium text-text">{item.label}</p>
                          <p className="text-xs text-text-secondary">{item.desc}</p>
                        </div>
                      </div>
                    </label>
                  ))}
                </div>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-3">{t('displayPreferences')}</h4>
                <div className="space-y-4">
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-2">{t('theme')}</label>
                    <div className="flex gap-2">
                      {(['light', 'dark', 'system'] as const).map(theme => (
                        <button key={theme} type="button" className={`flex-1 px-4 py-3 rounded-xl border-2 font-medium transition-all ${formData.theme === theme ? 'border-primary bg-primary/10 text-primary' : 'border-border text-text-secondary hover:border-primary/50'}`} onClick={() => handleChange('theme', theme)}>
                          <span className="capitalize">{theme}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-2">{t('dateFormat')}</label>
                    <select value="dd/mm/yyyy" className="w-full sm:w-48 px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing}>
                      <option value="dd/mm/yyyy">DD/MM/YYYY</option>
                      <option value="mm/dd/yyyy">MM/DD/YYYY</option>
                      <option value="yyyy-mm-dd">YYYY-MM-DD</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-2">{t('timeFormat')}</label>
                    <select value="24h" className="w-full sm:w-48 px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" disabled={!isEditing}>
                      <option value="24h">24 Hour</option>
                      <option value="12h">12 Hour</option>
                    </select>
                  </div>
                </div>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}