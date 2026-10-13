import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { Eye, EyeOff, Shield, AlertCircle, Loader2, LogIn, Smartphone, Fingerprint } from 'lucide-react';
import { Link } from 'react-router-dom';

export function Login() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const { login, isLoading } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [loginMethod, setLoginMethod] = useState<'credentials' | 'nationalId'>('credentials');
  const [nationalId, setNationalId] = useState('');

  const from = (location.state as any)?.from?.pathname || '/portal/dashboard';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    try {
      if (loginMethod === 'credentials') {
        if (!username || !password) {
          setError(t('usernamePasswordRequired') || 'Username and password are required');
          return;
        }
        await login(username, password);
      } else {
        if (!nationalId) {
          setError(t('nationalIdRequired') || 'National ID is required');
          return;
        }
        await login(nationalId, '', 'NATIONAL_ID');
      }
      navigate(from, { replace: true });
    } catch (err: any) {
      setError(err.message || t('loginFailed') || 'Login failed. Please try again.');
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg px-4 sm:px-6 lg:px-8">
      <div className="w-full max-w-md">
        {/* Header */}
        <div className="text-center mb-8">
          <Link to="/" className="inline-flex items-center gap-2 mb-6" aria-label="ITMS Home">
            <div className="w-10 h-10 rounded-lg bg-primary flex items-center justify-center">
              <span className="text-white font-bold text-lg">ITMS</span>
            </div>
            <span className="font-bold text-xl text-text">ITMS</span>
          </Link>
          <h1 className="text-2xl font-bold text-text mb-2">{t('signIn')}</h1>
          <p className="text-text-secondary">{t('loginSubtitle') || 'Sign in to access your traffic services'}</p>
        </div>

        {/* Login Method Tabs */}
        <div className="mb-6 bg-surface rounded-xl p-1 border border-border" role="tablist">
          <button
            role="tab"
            aria-selected={loginMethod === 'credentials'}
            onClick={() => { setLoginMethod('credentials'); setError(''); }}
            className={`flex-1 py-2.5 px-4 rounded-lg text-sm font-medium transition-all ${
              loginMethod === 'credentials'
                ? 'bg-primary text-white shadow-sm'
                : 'text-text-secondary hover:text-text hover:bg-surface-hover'
            }`}
          >
            <span className="flex items-center justify-center gap-2">
              <LogIn className="w-4 h-4" />
              {t('usernamePassword') || 'Username & Password'}
            </span>
          </button>
          <button
            role="tab"
            aria-selected={loginMethod === 'nationalId'}
            onClick={() => { setLoginMethod('nationalId'); setError(''); }}
            className={`flex-1 py-2.5 px-4 rounded-lg text-sm font-medium transition-all ${
              loginMethod === 'nationalId'
                ? 'bg-primary text-white shadow-sm'
                : 'text-text-secondary hover:text-text hover:bg-surface-hover'
            }`}
          >
            <span className="flex items-center justify-center gap-2">
              <Shield className="w-4 h-4" />
              {t('nationalIdLogin') || 'National Digital ID'}
            </span>
          </button>
        </div>

        {/* Error Message */}
        {error && (
          <div className="mb-6 p-4 bg-error/10 border border-error/20 rounded-xl text-error text-sm flex items-center gap-2" role="alert">
            <AlertCircle className="w-5 h-5 flex-shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {/* Login Form */}
        <form onSubmit={handleSubmit} className="space-y-5" noValidate>
          {loginMethod === 'credentials' ? (
            <>
              <div>
                <label htmlFor="username" className="block text-sm font-medium text-text-secondary mb-1.5">
                  {t('username')} <span className="text-error" aria-hidden="true">*</span>
                </label>
                <div className="relative">
                  <input
                    type="text"
                    id="username"
                    autoComplete="username"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    className="w-full px-4 py-3 pl-11 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                    placeholder={t('usernamePlaceholder') || 'Enter your username'}
                    disabled={isLoading}
                    aria-required="true"
                  />
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" aria-hidden="true">
                    <LogIn className="w-5 h-5" />
                  </span>
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label htmlFor="password" className="block text-sm font-medium text-text-secondary">
                    {t('password')} <span className="text-error" aria-hidden="true">*</span>
                  </label>
                  <Link
                    to="/forgot-password"
                    className="text-sm text-primary hover:underline"
                  >
                    {t('forgotPassword')}
                  </Link>
                </div>
                <div className="relative">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    id="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="w-full px-4 py-3 pl-11 pr-11 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                    placeholder={t('passwordPlaceholder') || 'Enter your password'}
                    disabled={isLoading}
                    aria-required="true"
                  />
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" aria-hidden="true">
                    <Shield className="w-5 h-5" />
                  </span>
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-text-muted hover:text-text transition-colors"
                    aria-label={showPassword ? t('hidePassword') : t('showPassword')}
                  >
                    {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                  </button>
                </div>
              </div>

              <div className="flex items-center justify-between">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    className="w-4 h-4 rounded border-border text-primary focus:ring-2 focus:ring-primary/20"
                  />
                  <span className="text-sm text-text-secondary">{t('rememberMe')}</span>
                </label>
              </div>
            </>
          ) : (
            <div>
              <label htmlFor="nationalId" className="block text-sm font-medium text-text-secondary mb-1.5">
                {t('nationalId')} <span className="text-error" aria-hidden="true">*</span>
              </label>
              <div className="relative">
                <input
                  type="text"
                  id="nationalId"
                  autoComplete="off"
                  value={nationalId}
                  onChange={(e) => setNationalId(e.target.value.replace(/\D/g, '').slice(0, 15))}
                  className="w-full px-4 py-3 pl-11 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                  placeholder="784-1990-1234567-1"
                  disabled={isLoading}
                  aria-required="true"
                  maxLength={18}
                />
                <span className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" aria-hidden="true">
                  <Shield className="w-5 h-5" />
                </span>
              </div>
              <p className="text-xs text-text-muted mt-1">{t('nationalIdHint') || '15-digit UAE National ID number'}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={isLoading}
            className="w-full py-3.5 px-6 bg-primary text-white font-semibold text-base rounded-xl hover:bg-primary-dark focus:outline-none focus:ring-2 focus:ring-primary/50 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
          >
            {isLoading ? (
              <>
                <Loader2 className="w-5 h-5 animate-spin mr-2" aria-hidden="true" />
                {t('signingIn') || 'Signing in...'}
              </>
            ) : (
              t(loginMethod === 'credentials' ? 'signIn' : 'signInWithNationalId')
            )}
          </button>
        </form>

        {/* Security notice */}
        <div className="mt-8 p-4 bg-primary/5 border border-primary/20 rounded-xl">
          <div className="flex items-start gap-3">
            <Shield className="w-5 h-5 text-primary flex-shrink-0 mt-0.5" />
            <div className="text-sm text-text-secondary">
              <p className="font-medium text-text mb-1">{t('securityNotice') || 'Your Security Matters'}</p>
              <ul className="space-y-1 text-text-muted">
                <li>• {t('securityTip1') || 'ITMS will never ask for your password via email or phone'}</li>
                <li>• {t('securityTip2') || 'Always verify the URL starts with https://itms.gov.ae'}</li>
                <li>• {t('securityTip3') || 'Enable two-factor authentication for extra security'}</li>
              </ul>
            </div>
          </div>
        </div>

        {/* Footer links */}
        <div className="mt-6 text-center space-y-3 text-sm">
          <Link to="/forgot-password" className="text-primary hover:underline block">
            {t('forgotPassword')}
          </Link>
          <p className="text-text-secondary">
            {t('noAccount') || 'Don\'t have an account?'}
            <Link to="/register" className="text-primary hover:underline font-medium ml-1">
              {t('register')}
            </Link>
          </p>
          <p className="text-text-muted">
            {t('visitor') || 'Visiting UAE?'} <Link to="/register" className="text-primary hover:underline font-medium">{t('registerAsVisitor') || 'Register as Visitor'}</Link>
          </p>
        </div>

        {/* Alternative login methods */}
        <div className="mt-8">
          <div className="relative mb-6">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-border" />
            </div>
            <div className="relative flex justify-center text-sm">
              <span className="px-4 bg-bg text-text-muted">{t('orContinueWith') || 'Or continue with'}</span>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <button
              type="button"
              disabled={isLoading}
              className="flex items-center justify-center gap-2 px-4 py-3 bg-surface border border-border rounded-xl text-text-secondary hover:text-text hover:bg-surface-hover hover:border-primary/50 transition-all disabled:opacity-50"
            >
              <Smartphone className="w-5 h-5" />
              <span>{t('mobileApp') || 'Mobile App'}</span>
            </button>
            <button
              type="button"
              disabled={isLoading}
              className="flex items-center justify-center gap-2 px-4 py-3 bg-surface border border-border rounded-xl text-text-secondary hover:text-text hover:bg-surface-hover hover:border-primary/50 transition-all disabled:opacity-50"
            >
              <Fingerprint className="w-5 h-5" />
              <span>{t('biometric') || 'Biometric'}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}