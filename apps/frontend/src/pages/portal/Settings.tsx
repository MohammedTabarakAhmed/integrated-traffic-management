import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Shield, Bell, Globe, Palette, Database, Download, Upload, Trash2, Key, ShieldCheck, AlertCircle, CheckCircle, Loader2, Save, Eye, EyeOff, Sun, Moon, Monitor } from 'lucide-react';

export function PortalSettings() {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState('account');
  const [saving, setSaving] = useState(false);
  const [successMessage, setSuccessMessage] = useState('');

  interface SettingsState {
  // Account
  username: string;
  email: string;
  phone: string;
  nationalId: string;
  language: string;
  // Notifications
  emailEnabled: boolean;
  emailViolations: boolean;
  emailPayments: boolean;
  emailAppointments: boolean;
  emailDocuments: boolean;
  emailMarketing: boolean;
  smsEnabled: boolean;
  smsViolations: boolean;
  smsPayments: boolean;
  smsAppointments: boolean;
  pushEnabled: boolean;
  pushViolations: boolean;
  pushPayments: boolean;
  pushAppointments: boolean;
  // Appearance
  theme: 'system' | 'light' | 'dark';
  appearanceLanguage: string;
  dateFormat: string;
  timeFormat: string;
  compactMode: boolean;
  animationsEnabled: boolean;
  // Security
  twoFactorEnabled: boolean;
  loginAlerts: boolean;
  sessionTimeout: number;
  // Data
  dataRetention: number;
  analyticsEnabled: boolean;
  crashReporting: boolean;
}

export function PortalSettings() {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState('account');
  const [saving, setSaving] = useState(false);
  const [successMessage, setSuccessMessage] = useState('');

  const tabs = [
    { id: 'account', label: t('accountSettings') || 'Account', icon: Shield },
    { id: 'notifications', label: t('notifications') || 'Notifications', icon: Bell },
    { id: 'appearance', label: t('appearance') || 'Appearance', icon: Palette },
    { id: 'data', label: t('dataPrivacy') || 'Data & Privacy', icon: Database },
    { id: 'security', label: t('security') || 'Security', icon: ShieldCheck },
  ];

  const [settings, setSettings] = useState<SettingsState>({
    // Account
    username: '',
    email: '',
    phone: '',
    nationalId: '',
    language: 'en',
    // Notifications
    emailEnabled: true,
    emailViolations: true,
    emailPayments: true,
    emailAppointments: true,
    emailDocuments: true,
    emailMarketing: false,
    smsEnabled: true,
    smsViolations: true,
    smsPayments: true,
    smsAppointments: true,
    pushEnabled: true,
    pushViolations: true,
    pushPayments: true,
    pushAppointments: true,
    // Appearance
    theme: 'system',
    appearanceLanguage: 'en',
    dateFormat: 'dd/mm/yyyy',
    timeFormat: '24h',
    compactMode: false,
    animationsEnabled: true,
    // Security
    twoFactorEnabled: false,
    loginAlerts: true,
    sessionTimeout: 30,
    // Data
    dataRetention: 365,
    analyticsEnabled: true,
    crashReporting: true,
  });

  const handleSave = async () => {
    setSaving(true);
    setSuccessMessage('');
    try {
      await new Promise(resolve => setTimeout(resolve, 1000));
      setSuccessMessage('Settings saved successfully');
    } catch (error) {
      setSuccessMessage('Failed to save settings');
    } finally {
      setSaving(false);
    }
  };

  const handleChange = (key: keyof SettingsState, value: any) => {
    setSettings(prev => ({ ...prev, [key]: value }));
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-text">{t('settings') || 'Settings'}</h1>
          <p className="text-text-secondary">{t('settingsSubtitle') || 'Manage your account settings and preferences'}</p>
        </div>
        <button onClick={handleSave} disabled={saving} className="px-6 py-3 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors disabled:opacity-50 flex items-center gap-2">
          {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
          {saving ? t('saving') : t('saveChanges')}
        </button>
      </div>

      {successMessage && (
        <div className="p-4 bg-green-50 border border-green-200 dark:bg-green-900/20 dark:border-green-800 rounded-xl flex items-center gap-3 text-green-700 dark:text-green-400" role="alert">
          <CheckCircle className="w-5 h-5" />
          <span>{successMessage}</span>
        </div>
      )}

      <div className="bg-surface rounded-2xl border border-border">
        <div className="border-b border-border">
          <nav className="flex flex-wrap -mb-px" aria-label="Settings tabs">
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
          {/* Account Tab */}
          {activeTab === 'account' && (
            <form className="space-y-6 max-w-2xl">
              <h3 className="text-lg font-semibold text-text">{t('accountInformation')}</h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('username')} <span className="text-error">*</span></label>
                  <input value={settings.username} onChange={(e) => setSettings({...settings, username: e.target.value})} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('email')}</label>
                  <input type="email" value={settings.email} onChange={(e) => setSettings({...settings, email: e.target.value})} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('phone')}</label>
                  <input type="tel" value={settings.phone} onChange={(e) => setSettings({...settings, phone: e.target.value})} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('nationalId')}</label>
                  <input value={settings.nationalId} onChange={(e) => setSettings({...settings, nationalId: e.target.value})} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('preferredLanguage')}</label>
                <select value={settings.language} onChange={(e) => setSettings({...settings, language: e.target.value})} className="w-full sm:w-48 px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
                  <option value="en">English</option>
                  <option value="ar">Arabic</option>
                </select>
              </div>
            </form>
          )}

          {/* Notifications Tab */}
          {activeTab === 'notifications' && (
            <div className="space-y-6">
              <div className="p-4 bg-surface border border-border rounded-xl">
                <div className="flex items-center justify-between mb-4">
                  <h4 className="font-medium text-text">{t('emailNotifications')}</h4>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" checked={settings.emailEnabled} onChange={(e) => setSettings({...settings, emailEnabled: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" />
                    <span className="text-sm text-text-secondary">{t('enableAll') || 'Enable all email notifications'}</span>
                  </label>
                </div>
                <div className="space-y-3">
                  {[
                    { key: 'emailViolations', label: 'Violation alerts', desc: 'New violations and due dates' },
                    { key: 'emailPayments', label: 'Payment confirmations', desc: 'Receipts and payment status' },
                    { key: 'emailAppointments', label: 'Appointment reminders', desc: 'Upcoming and confirmed appointments' },
                    { key: 'emailDocuments', label: 'Document updates', desc: 'Verification status and expiry alerts' },
                    { key: 'emailMarketing', label: 'Promotional offers', desc: 'Service updates and special offers' },
                  ].map(item => (
                    <label key={item.key} className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                      <div className="flex items-center gap-3">
                        <input type="checkbox" checked={settings[item.key]} onChange={(e) => setSettings({...settings, [item.key]: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" disabled={!settings.emailEnabled} />
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
                <div className="flex items-center justify-between mb-4">
                  <h4 className="font-medium text-text">{t('smsNotifications')}</h4>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" checked={settings.smsEnabled} onChange={(e) => setSettings({...settings, smsEnabled: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" />
                    <span className="text-sm text-text-secondary">{t('enableAll')}</span>
                  </label>
                </div>
                <div className="space-y-3">
                  {[
                    { key: 'smsViolations', label: 'Violation alerts', desc: 'Urgent violations and due dates' },
                    { key: 'smsPayments', label: 'Payment confirmations', desc: 'Payment receipts via SMS' },
                    { key: 'smsAppointments', label: 'Appointment reminders', desc: 'Upcoming appointment alerts' },
                  ].map(item => (
                    <label key={item.key} className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                      <div className="flex items-center gap-3">
                        <input type="checkbox" checked={settings[item.key]} onChange={(e) => setSettings({...settings, [item.key]: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" disabled={!settings.smsEnabled} />
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
                <div className="flex items-center justify-between mb-4">
                  <h4 className="font-medium text-text">{t('pushNotifications')}</h4>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" checked={settings.pushEnabled} onChange={(e) => setSettings({...settings, pushEnabled: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" />
                    <span className="text-sm text-text-secondary">{t('enableAll')}</span>
                  </label>
                </div>
                <div className="space-y-3">
                  {[
                    { key: 'pushViolations', label: 'Violation alerts', desc: 'Real-time violation notifications' },
                    { key: 'pushPayments', label: 'Payment confirmations', desc: 'Instant payment receipts' },
                    { key: 'pushAppointments', label: 'Appointment reminders', desc: 'Upcoming appointment alerts' },
                  ].map(item => (
                    <label key={item.key} className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                      <div className="flex items-center gap-3">
                        <input type="checkbox" checked={settings[item.key]} onChange={(e) => setSettings({...settings, [item.key]: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" disabled={!settings.pushEnabled} />
                        <div>
                          <p className="font-medium text-text">{item.label}</p>
                          <p className="text-xs text-text-secondary">{item.desc}</p>
                        </div>
                      </div>
                    </label>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* Appearance Tab */}
          {activeTab === 'appearance' && (
            <div className="space-y-6">
              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('theme')}</h4>
                <div className="grid grid-cols-3 gap-4">
                  {(['light', 'dark', 'system'] as const).map(theme => (
                    <button key={theme} type="button" onClick={() => setSettings({...settings, theme})} className={`p-4 rounded-xl border-2 font-medium transition-all text-center ${settings.theme === theme ? 'border-primary bg-primary/10 text-primary' : 'border-border text-text-secondary hover:border-primary/50'}`}>
                      <div className="text-2xl mb-2">
                        {theme === 'light' && <Sun className="w-8 h-8 mx-auto" />}
                        {theme === 'dark' && <Moon className="w-8 h-8 mx-auto" />}
                        {theme === 'system' && <Monitor className="w-8 h-8 mx-auto" />}
                      </div>
                      <span className="capitalize font-medium">{theme}</span>
                      <p className="text-xs text-text-secondary mt-1">
                        {theme === 'light' && 'Always light'}
                        {theme === 'dark' && 'Always dark'}
                        {theme === 'system' && 'Match system'}
                      </p>
                    </button>
                  ))}
                </div>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('language')}</h4>
                <div className="flex gap-4">
                  {(['en', 'ar'] as const).map(lang => (
                    <button key={lang} type="button" onClick={() => setSettings({...settings, language: lang})} className={`flex-1 px-6 py-4 rounded-xl border-2 font-medium transition-all ${settings.language === lang ? 'border-primary bg-primary/10 text-primary' : 'border-border text-text-secondary hover:border-primary/50'}`}>
                      {lang === 'en' ? 'English' : 'العربية'}
                    </button>
                  ))}
                </div>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('dateTimeFormat')}</h4>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-2">{t('dateFormat')}</label>
                    <select value={settings.dateFormat} onChange={(e) => setSettings({...settings, dateFormat: e.target.value})} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
                      <option value="dd/mm/yyyy">DD/MM/YYYY</option>
                      <option value="mm/dd/yyyy">MM/DD/YYYY</option>
                      <option value="yyyy-mm-dd">YYYY-MM-DD</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-2">{t('timeFormat')}</label>
                    <select value={settings.timeFormat} onChange={(e) => setSettings({...settings, timeFormat: e.target.value})} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
                      <option value="24h">24 Hour</option>
                      <option value="12h">12 Hour</option>
                    </select>
                  </div>
                </div>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('displayOptions')}</h4>
                <div className="space-y-4">
                  <label className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                    <div>
                      <p className="font-medium text-text">{t('compactMode')}</p>
                      <p className="text-sm text-text-secondary">{t('compactModeDesc')}</p>
                    </div>
                    <input type="checkbox" checked={settings.compactMode} onChange={(e) => setSettings({...settings, compactMode: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" />
                  </label>
                  <label className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                    <div>
                      <p className="font-medium text-text">{t('animationsEnabled')}</p>
                      <p className="text-sm text-text-secondary">{t('animationsEnabledDesc')}</p>
                    </div>
                    <input type="checkbox" checked={settings.animationsEnabled} onChange={(e) => setSettings({...settings, animationsEnabled: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" />
                  </label>
                </div>
              </div>
            </div>
          )}

          {/* Data & Privacy Tab */}
          {activeTab === 'data' && (
            <div className="space-y-6">
              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('dataRetention')}</h4>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-2">{t('retentionPeriod')}</label>
                    <select value={settings.dataRetention} onChange={(e) => setSettings({...settings, dataRetention: parseInt(e.target.value)})} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
                      <option value="90">90 days</option>
                      <option value="180">180 days</option>
                      <option value="365">1 year</option>
                      <option value="730">2 years</option>
                      <option value="1095">3 years</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-2">{t('autoDelete')}</label>
                    <label className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                      <div>
                        <p className="font-medium text-text">{t('autoDeleteExpired')}</p>
                        <p className="text-sm text-text-secondary">{t('autoDeleteExpiredDesc')}</p>
                      </div>
                      <input type="checkbox" checked={true} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" />
                    </label>
                  </div>
                </div>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('analyticsPrivacy')}</h4>
                <div className="space-y-3">
                  {[
                    { key: 'analyticsEnabled', label: 'Usage analytics', desc: 'Help improve ITMS with anonymous usage data' },
                    { key: 'crashReporting', label: 'Crash reporting', desc: 'Automatically send crash reports for debugging' },
                  ].map(item => (
                    <label key={item.key} className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                      <div className="flex items-center gap-3">
                        <input type="checkbox" checked={settings[item.key]} onChange={(e) => setSettings({...settings, [item.key]: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" />
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
                <h4 className="font-medium text-text mb-4">{t('dataManagement')}</h4>
                <div className="flex flex-wrap gap-3">
                  <button className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-blue-600 bg-blue-50 border border-blue-200 rounded-lg hover:bg-blue-100 transition-colors">
                    <Download className="w-4 h-4" />
                    {t('downloadMyData') || 'Download My Data'}
                  </button>
                  <button className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-orange-600 bg-orange-50 border border-orange-200 rounded-lg hover:bg-orange-100 transition-colors">
                    <Upload className="w-4 h-4" />
                    {t('requestDataCorrection') || 'Request Data Correction'}
                  </button>
                  <button className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-red-600 bg-red-50 border border-red-200 rounded-lg hover:bg-red-100 transition-colors">
                    <Trash2 className="w-4 h-4" />
                    {t('requestAccountDeletion') || 'Request Account Deletion'}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Security Tab */}
          {activeTab === 'security' && (
            <div className="space-y-6">
              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('twoFactorAuthentication')}</h4>
                <div className="flex items-center justify-between">
                  <div>
                    <h5 className="font-medium text-text">{t('authenticatorApp')}</h5>
                    <p className="text-sm text-text-secondary">{t('authenticatorAppDesc')}</p>
                  </div>
                  <button className={`px-4 py-2 rounded-lg font-medium transition-all ${settings.twoFactorEnabled ? 'bg-primary text-white' : 'bg-surface border border-border text-text-secondary hover:bg-surface-hover'}`} onClick={() => setSettings({...settings, twoFactorEnabled: !settings.twoFactorEnabled})}>
                    {settings.twoFactorEnabled ? t('enabled') : t('enable')}
                  </button>
                </div>
                {settings.twoFactorEnabled && (
                  <div className="mt-4 p-3 bg-green-50 border border-green-200 rounded-lg">
                    <p className="text-sm text-green-700">{t('backupCodesNote')}</p>
                    <button className="mt-2 text-sm text-primary hover:underline">{t('viewBackupCodes')}</button>
                  </div>
                )}
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('loginAlerts')}</h4>
                <label className="flex items-center justify-between p-3 bg-bg rounded-lg border border-border">
                  <div>
                    <p className="font-medium text-text">{t('newDeviceLogin')}</p>
                    <p className="text-sm text-text-secondary">{t('newDeviceLoginDesc')}</p>
                  </div>
                  <input type="checkbox" checked={settings.loginAlerts} onChange={(e) => setSettings({...settings, loginAlerts: e.target.checked})} className="w-5 h-5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20" />
                </label>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('sessionManagement')}</h4>
                <div className="space-y-3">
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-2">{t('sessionTimeout')}</label>
                    <select value={settings.sessionTimeout} onChange={(e) => setSettings({...settings, sessionTimeout: parseInt(e.target.value)})} className="w-full sm:w-48 px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
                      <option value="15">15 minutes</option>
                      <option value="30">30 minutes</option>
                      <option value="60">1 hour</option>
                      <option value="120">2 hours</option>
                    </select>
                  </div>
                  <div className="p-3 bg-bg rounded-lg border border-border">
                    <p className="font-medium text-text mb-2">{t('activeSessions')}</p>
                    <div className="space-y-2">
                      <div className="flex items-center justify-between p-2 bg-surface rounded-lg border border-border">
                        <div className="flex items-center gap-3">
                          <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center"><Shield className="w-4 h-4 text-primary" /></div>
                          <div>
                            <p className="font-medium text-text">{t('currentSession')}</p>
                            <p className="text-sm text-text-secondary">{t('thisDevice')}</p>
                          </div>
                        </div>
                        <span className="px-2 py-1 text-xs bg-green-50 text-green-700 rounded-full">{t('active')}</span>
                      </div>
                      <div className="flex items-center justify-between p-2 bg-surface rounded-lg border border-border">
                        <div className="flex items-center gap-3">
                          <div className="w-8 h-8 rounded-full bg-gray-200/50 flex items-center justify-center"><Key className="w-4 h-4 text-text-muted" /></div>
                          <div>
                            <p className="font-medium text-text">Mobile App</p>
                            <p className="text-sm text-text-secondary">{t('lastActive')}</p>
                          </div>
                        </div>
                        <button className="px-3 py-1 text-xs text-red-600 hover:text-red-700">{t('revoke')}</button>
                      </div>
                    </div>
                    <button className="text-sm text-primary hover:underline">{t('revokeAllOtherSessions')}</button>
                  </div>
                </div>
              </div>

              <div className="p-4 bg-surface border border-border rounded-xl">
                <h4 className="font-medium text-text mb-4">{t('changePassword')}</h4>
                <form className="space-y-4 max-w-md">
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('currentPassword')}</label>
                    <input type="password" className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" placeholder="••••••••" />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('newPassword')}</label>
                    <input type="password" className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" placeholder="••••••••" />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-text-secondary mb-1.5">{t('confirmNewPassword')}</label>
                    <input type="password" className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" placeholder="••••••••" />
                  </div>
                  <button type="submit" className="px-4 py-2 text-sm font-medium text-primary bg-primary/10 rounded-lg hover:bg-primary/20 transition-colors">{t('updatePassword')}</button>
                </form>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}