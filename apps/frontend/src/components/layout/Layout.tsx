import { Outlet, NavLink, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../hooks/useAuth';
import {
  LayoutDashboard,
  Settings,
  LogOut,
  User,
  Bell,
  Menu,
  X,
  ChevronDown,
  Sun,
  Moon,
  Monitor,
} from 'lucide-react';
import { useState, useEffect } from 'react';

interface LayoutProps {
  backoffice?: boolean;
}

export function Layout({ backoffice = false }: LayoutProps) {
  const { t, i18n } = useTranslation();
  const { user, logout, setLanguage } = useAuth();
  const navigate = useNavigate();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
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
    setLanguage(lang);
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
    document.documentElement.lang = lang;
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const isRTL = i18n.language === 'ar';

  const navItems = backoffice ? [
    { path: '/backoffice/dashboard', label: t('dashboard'), icon: LayoutDashboard },
    { path: '/backoffice/services', label: t('services'), icon: Settings },
    { path: '/backoffice/transactions', label: t('transactions'), icon: LayoutDashboard },
    { path: '/backoffice/users', label: t('users'), icon: User },
    { path: '/backoffice/reports', label: t('reports'), icon: LayoutDashboard },
  ] : [
    { path: '/portal/dashboard', label: t('dashboard'), icon: LayoutDashboard },
    { path: '/portal/services', label: t('services'), icon: Settings },
    { path: '/portal/licences', label: t('licences'), icon: User },
    { path: '/portal/vehicles', label: t('vehicles'), icon: Settings },
    { path: '/portal/violations', label: t('violations'), icon: Settings },
    { path: '/portal/payments', label: t('payments'), icon: Settings },
    { path: '/portal/appointments', label: t('appointments'), icon: Settings },
    { path: '/portal/documents', label: t('documents'), icon: Settings },
    { path: '/portal/profile', label: t('profile'), icon: User },
    { path: '/portal/settings', label: t('settings'), icon: Settings },
  ];

  return (
    <div className={`min-h-screen flex ${isRTL ? 'rtl' : 'ltr'}`}>
      {/* Mobile sidebar overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 bg-black/50 z-40 lg:hidden"
          onClick={() => setSidebarOpen(false)}
          aria-hidden="true"
        />
      )}

      {/* Sidebar */}
      <aside
        className={`
          fixed lg:static inset-y-0 z-50 w-64 transform transition-transform duration-300 ease-in-out
          bg-surface border-r border-border
          ${sidebarOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'}
          ${isRTL ? 'right-0' : 'left-0'}
        `}
        role="navigation"
        aria-label={backoffice ? 'Backoffice navigation' : 'Portal navigation'}
      >
        <div className="flex flex-col h-full">
          {/* Logo */}
          <div className="flex items-center justify-between h-16 px-4 border-b border-border">
            <NavLink to={backoffice ? '/backoffice/dashboard' : '/portal/dashboard'} className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
                <span className="text-white font-bold text-sm">ITMS</span>
              </div>
              <span className="font-semibold text-lg text-text">{t('appName')}</span>
            </NavLink>
            <button
              className="lg:hidden p-2 rounded-lg hover:bg-surface-hover"
              onClick={() => setSidebarOpen(false)}
              aria-label={t('closeMenu')}
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Navigation */}
          <nav className="flex-1 p-4 space-y-1 overflow-y-auto" aria-label="Main navigation">
            {navItems.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                onClick={() => setSidebarOpen(false)}
                className={({ isActive }) => `
                  flex items-center gap-3 px-3 py-2.5 rounded-lg text-text-secondary
                  transition-colors duration-150
                  ${isActive
                    ? 'bg-primary/10 text-primary font-medium'
                    : 'hover:bg-surface-hover hover:text-text'
                  }
                `}
                aria-current={item.path === window.location.pathname ? 'page' : undefined}
              >
                <item.icon className="w-5 h-5 flex-shrink-0" aria-hidden="true" />
                <span>{item.label}</span>
              </NavLink>
            ))}
          </nav>

          {/* User section */}
          <div className="p-4 border-t border-border">
            <div className="flex items-center gap-3 p-2 rounded-lg bg-surface-hover">
              <div className="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center">
                <User className="w-5 h-5 text-primary" aria-hidden="true" />
              </div>
              <div className="flex-1 min-w-0">
                <p className="font-medium text-sm text-text truncate">{user?.fullName || user?.username}</p>
                <p className="text-xs text-text-muted truncate">{user?.email}</p>
              </div>
            </div>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <div className={`flex-1 flex flex-col ${isRTL ? 'mr-0 ml-0' : 'ml-0 mr-0'} lg:ml-64`}>
        {/* Top bar */}
        <header
          className={`
            sticky top-0 z-30 h-16 flex items-center justify-between px-4 sm:px-6
            bg-surface/95 backdrop-blur-sm border-b border-border
            ${isScrolled ? 'shadow-sm' : ''}
          `}
          role="banner"
        >
          <div className="flex items-center gap-4">
            <button
              className="lg:hidden p-2 rounded-lg hover:bg-surface-hover"
              onClick={() => setSidebarOpen(true)}
              aria-label={t('openMenu')}
            >
              <Menu className="w-6 h-6" />
            </button>

            {/* Search */}
            <div className="hidden md:flex flex-1 max-w-md">
              <div className="relative w-full">
                <search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" aria-hidden="true" />
                <input
                  type="search"
                  placeholder={t('searchPlaceholder')}
                  className="w-full pl-10 pr-4 py-2 bg-bg border border-border rounded-lg text-sm text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
                  aria-label={t('search')}
                />
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2 sm:gap-4">
            {/* Notifications */}
            <button className="relative p-2 rounded-lg hover:bg-surface-hover transition-colors" aria-label={t('notifications')}>
              <Bell className="w-5 h-5 text-text-secondary" />
              <span className="absolute top-1 right-1 w-2 h-2 bg-error rounded-full" aria-hidden="true" />
            </button>

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

            {/* User menu */}
            <div className="relative">
              <button
                onClick={() => setUserMenuOpen(!userMenuOpen)}
                className="flex items-center gap-2 p-2 rounded-lg hover:bg-surface-hover transition-colors"
                aria-expanded={userMenuOpen}
                aria-haspopup="true"
              >
                <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center">
                  <User className="w-4 h-4 text-primary" />
                </div>
                <span className="hidden sm:block text-sm font-medium text-text">{user?.fullName || user?.username}</span>
                <ChevronDown className="w-4 h-4 text-text-muted" />
              </button>

              {userMenuOpen && (
                <>
                  <div className="fixed inset-0 z-40" onClick={() => setUserMenuOpen(false)} aria-hidden="true" />
                  <div className="absolute right-0 mt-2 w-48 bg-surface rounded-lg shadow-lg border border-border py-1 z-50">
                    <NavLink
                      to={backoffice ? '/backoffice/profile' : '/portal/profile'}
                      onClick={() => setUserMenuOpen(false)}
                      className="flex items-center gap-2 px-4 py-2 text-sm text-text-secondary hover:bg-surface-hover hover:text-text"
                    >
                      <User className="w-4 h-4" />
                      {t('profile')}
                    </NavLink>
                    <NavLink
                      to={backoffice ? '/backoffice/settings' : '/portal/settings'}
                      onClick={() => setUserMenuOpen(false)}
                      className="flex items-center gap-2 px-4 py-2 text-sm text-text-secondary hover:bg-surface-hover hover:text-text"
                    >
                      <Settings className="w-4 h-4" />
                      {t('settings')}
                    </NavLink>
                    <hr className="my-1 border-border" />
                    <button
                      onClick={handleLogout}
                      className="flex items-center gap-2 w-full px-4 py-2 text-sm text-error hover:bg-surface-hover"
                    >
                      <LogOut className="w-4 h-4" />
                      {t('logout')}
                    </button>
                  </div>
                </>
              )}
            </div>
          </div>
        </header>

        <main className="flex-1 p-4 sm:p-6 lg:p-8" role="main" id="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}