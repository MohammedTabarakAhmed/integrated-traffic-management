import { Outlet, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Menu, X, Sun, Moon, Monitor, LogIn, UserPlus } from 'lucide-react';
import { useState, useEffect } from 'react';

export function PublicLayout() {
  const { t, i18n } = useTranslation();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [theme, setTheme] = useState<'light' | 'dark' | 'system'>('system');
  const [isScrolled, setIsScrolled] = useState(false);

  useEffect(() => {
    const savedTheme = localStorage.getItem('theme') as 'light' | 'dark' | 'system' || 'system';
    setTheme(savedTheme);
    applyTheme(savedTheme);
  }, []);

  useEffect(() => {
    const handleScroll = () => setIsScrolled(window.scrollY > 8);
    window.addEventListener('scroll', handleScroll);
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  const applyTheme = (newTheme: 'light' | 'dark' | 'system') => {
    const root = document.documentElement;
    if (newTheme === 'system') {
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      root.classList.toggle('dark', prefersDark);
    } else {
      root.classList.toggle('dark', newTheme === 'dark');
    }
    localStorage.setItem('theme', newTheme);
  };

  const handleThemeChange = (newTheme: 'light' | 'dark' | 'system') => {
    setTheme(newTheme);
    applyTheme(newTheme);
  };

  const handleLanguageChange = (lang: 'en' | 'ar') => {
    i18n.changeLanguage(lang);
    localStorage.setItem('i18nextLng', lang);
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
    document.documentElement.lang = lang;
  };

  const isRTL = i18n.language === 'ar';

  return (
    <div className={`min-h-screen flex flex-col ${isRTL ? 'rtl' : 'ltr'}`}>
      {/* Header */}
      <header
        className={`
          sticky top-0 z-50 h-16 flex items-center justify-between px-4 sm:px-6
          bg-surface/95 backdrop-blur-sm border-b border-border
          ${isScrolled ? 'shadow-sm' : ''}
        `}
        role="banner"
      >
        <div className="flex items-center gap-4">
          <Link to="/" className="flex items-center gap-2" aria-label={t('appFullName')}>
            <div className="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
              <span className="text-white font-bold text-sm">ITMS</span>
            </div>
            <span className="font-semibold text-lg text-text hidden sm:block">{t('appName')}</span>
          </Link>

          <nav className="hidden md:flex items-center gap-6" aria-label="Main navigation">
            <Link to="/" className="text-sm font-medium text-text-secondary hover:text-text transition-colors">
              {t('portal')}
            </Link>
            <Link to="/services" className="text-sm font-medium text-text-secondary hover:text-text transition-colors">
              {t('services')}
            </Link>
            <Link to="/fee-calculator" className="text-sm font-medium text-text-secondary hover:text-text transition-colors">
              {t('feeCalculator') || 'Fee Calculator'}
            </Link>
            <Link to="/centre-finder" className="text-sm font-medium text-text-secondary hover:text-text transition-colors">
              {t('centreFinder') || 'Centre Finder'}
            </Link>
            <Link to="/certificate-verify" className="text-sm font-medium text-text-secondary hover:text-text transition-colors">
              {t('certificateVerification') || 'Verify Certificate'}
            </Link>
            <Link to="/violation-enquiry" className="text-sm font-medium text-text-secondary hover:text-text transition-colors">
              {t('violationEnquiry') || 'Violation Enquiry'}
            </Link>
          </nav>
        </div>

        <div className="flex items-center gap-3">
          {/* Theme toggle */}
          <div className="hidden sm:flex items-center gap-1 bg-bg rounded-lg p-1 border border-border" role="group" aria-label={t('theme')}>
            {(['light', 'dark', 'system'] as const).map((t) => (
              <button
                key={t}
                onClick={() => handleThemeChange(t)}
                className={`p-2 rounded-md text-sm transition-all ${
                  theme === t
                    ? 'bg-primary text-white'
                    : 'text-text-secondary hover:text-text hover:bg-surface-hover'
                }`}
                aria-pressed={theme === t}
              >
                {t === 'light' && <Sun className="w-4 h-4" />}
                {t === 'dark' && <Moon className="w-4 h-4" />}
                {t === 'system' && <Monitor className="w-4 h-4" />}
              </button>
            ))}
          </div>

          {/* Language toggle */}
          <div className="flex items-center gap-1 bg-bg rounded-lg p-1 border border-border" role="group" aria-label={t('language')}>
            {(['en', 'ar'] as const).map((lang) => (
              <button
                key={lang}
                onClick={() => handleLanguageChange(lang)}
                className={`px-3 py-1.5 rounded-md text-sm font-medium transition-all ${
                  i18n.language === lang
                    ? 'bg-primary text-white'
                    : 'text-text-secondary hover:text-text hover:bg-surface-hover'
                }`}
                aria-pressed={i18n.language === lang}
              >
                {lang === 'en' ? 'EN' : 'عربي'}
              </button>
            ))}
          </div>

          {/* Auth buttons */}
          <div className="flex items-center gap-2">
            <Link
              to="/login"
              className="hidden sm:flex items-center gap-2 px-4 py-2 text-sm font-medium text-text-secondary hover:text-text transition-colors"
            >
              <LogIn className="w-4 h-4" />
              {t('login')}
            </Link>
            <Link
              to="/register"
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-lg hover:bg-primary-dark transition-colors"
            >
              <UserPlus className="w-4 h-4" />
              <span className="hidden sm:inline">{t('register')}</span>
            </Link>

            {/* Mobile menu button */}
            <button
              className="md:hidden p-2 rounded-lg hover:bg-surface-hover"
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              aria-expanded={mobileMenuOpen}
              aria-label={mobileMenuOpen ? 'Close menu' : 'Open menu'}
            >
              {mobileMenuOpen ? <X className="w-6 h-6" /> : <Menu className="w-6 h-6" />}
            </button>
          </div>
        </div>
      </header>

      {/* Mobile menu */}
      {mobileMenuOpen && (
        <div className="md:hidden fixed top-16 left-0 right-0 bg-surface border-b border-border shadow-lg z-40 p-4">
          <nav className="flex flex-col gap-2">
            <Link to="/" className="px-4 py-3 text-text-secondary hover:text-text hover:bg-surface-hover rounded-lg transition-colors" onClick={() => setMobileMenuOpen(false)}>
              {t('portal')}
            </Link>
            <Link to="/services" className="px-4 py-3 text-text-secondary hover:text-text hover:bg-surface-hover rounded-lg transition-colors" onClick={() => setMobileMenuOpen(false)}>
              {t('services')}
            </Link>
            <Link to="/fee-calculator" className="px-4 py-3 text-text-secondary hover:text-text hover:bg-surface-hover rounded-lg transition-colors" onClick={() => setMobileMenuOpen(false)}>
              {t('feeCalculator') || 'Fee Calculator'}
            </Link>
            <Link to="/centre-finder" className="px-4 py-3 text-text-secondary hover:text-text hover:bg-surface-hover rounded-lg transition-colors" onClick={() => setMobileMenuOpen(false)}>
              {t('centreFinder') || 'Centre Finder'}
            </Link>
            <Link to="/certificate-verify" className="px-4 py-3 text-text-secondary hover:text-text hover:bg-surface-hover rounded-lg transition-colors" onClick={() => setMobileMenuOpen(false)}>
              {t('certificateVerification') || 'Verify Certificate'}
            </Link>
            <Link to="/violation-enquiry" className="px-4 py-3 text-text-secondary hover:text-text hover:bg-surface-hover rounded-lg transition-colors" onClick={() => setMobileMenuOpen(false)}>
              {t('violationEnquiry') || 'Violation Enquiry'}
            </Link>
            <hr className="my-3 border-border" />
            <Link to="/login" className="px-4 py-3 text-text-secondary hover:text-text hover:bg-surface-hover rounded-lg transition-colors" onClick={() => setMobileMenuOpen(false)}>
              {t('login')}
            </Link>
            <Link to="/register" className="px-4 py-3 text-center text-white bg-primary rounded-lg hover:bg-primary-dark transition-colors" onClick={() => setMobileMenuOpen(false)}>
              {t('register')}
            </Link>
          </nav>
        </div>
      )}

      <main className="flex-1" role="main" id="main-content">
        <Outlet />
      </main>

      {/* Footer */}
      <footer className="bg-surface border-t border-border" role="contentinfo">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-8">
            <div>
              <Link to="/" className="flex items-center gap-2" aria-label={t('appFullName')}>
                <div className="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
                  <span className="text-white font-bold text-sm">ITMS</span>
                </div>
                <span className="font-semibold text-lg text-text">{t('appName')}</span>
              </Link>
              <p className="mt-4 text-sm text-text-secondary max-w-xs">{t('appFullName')}</p>
            </div>
            <div>
              <h3 className="font-semibold text-text mb-4">{t('services')}</h3>
              <ul className="space-y-2 text-sm text-text-secondary">
                <li><Link to="/services" className="hover:text-text transition-colors">{t('licences')}</Link></li>
                <li><Link to="/services" className="hover:text-text transition-colors">{t('vehicles')}</Link></li>
                <li><Link to="/services" className="hover:text-text transition-colors">{t('violations')}</Link></li>
                <li><Link to="/services" className="hover:text-text transition-colors">{t('payments')}</Link></li>
              </ul>
            </div>
            <div>
              <h3 className="font-semibold text-text mb-4">{t('help')}</h3>
              <ul className="space-y-2 text-sm text-text-secondary">
                <li><Link to="/services" className="hover:text-text transition-colors">{t('serviceGuide') || 'Service Guide'}</Link></li>
                <li><Link to="/fee-calculator" className="hover:text-text transition-colors">{t('feeCalculator') || 'Fee Calculator'}</Link></li>
                <li><Link to="/centre-finder" className="hover:text-text transition-colors">{t('centreFinder') || 'Centre Finder'}</Link></li>
                <li><Link to="/certificate-verify" className="hover:text-text transition-colors">{t('certificateVerification') || 'Verify Certificate'}</Link></li>
              </ul>
            </div>
            <div>
              <h3 className="font-semibold text-text mb-4">{t('contact')}</h3>
              <ul className="space-y-2 text-sm text-text-secondary">
                <li>800-ITMS (4867)</li>
                <li>info@itms.gov.ae</li>
                <li>Available 24/7</li>
              </ul>
            </div>
          </div>
          <div className="mt-8 pt-8 border-t border-border flex flex-col md:flex-row justify-between items-center gap-4">
            <p className="text-sm text-text-muted">© 2024 ITMS. All rights reserved.</p>
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-1 bg-bg rounded-lg p-1 border border-border" role="group" aria-label={t('language')}>
                {(['en', 'ar'] as const).map((lang) => (
                  <button
                    key={lang}
                    onClick={() => handleLanguageChange(lang)}
                    className={`px-3 py-1.5 rounded-md text-sm font-medium transition-all ${
                      i18n.language === lang
                        ? 'bg-primary text-white'
                        : 'text-text-secondary hover:text-text hover:bg-surface-hover'
                    }`}
                    aria-pressed={i18n.language === lang}
                  >
                    {lang === 'en' ? 'EN' : 'عربي'}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}