import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Home, Search, ArrowLeft, RotateCcw } from 'lucide-react';

export function NotFound() {
  const { t } = useTranslation();

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg px-4">
      <div className="text-center max-w-md">
        <div className="mb-8">
          <div className="inline-flex items-center justify-center w-24 h-24 rounded-full bg-primary/10 mb-6">
            <Search className="w-12 h-12 text-primary" />
          </div>
          <h1 className="text-4xl font-bold text-text mb-2">404</h1>
          <p className="text-xl text-text-secondary mb-4">{t('pageNotFound') || 'Page Not Found'}</p>
          <p className="text-text-muted">{t('pageNotFoundDesc') || 'Sorry, we couldn\'t find the page you\'re looking for. It might have been moved or doesn\'t exist.'}</p>
        </div>

        <div className="space-y-4">
          <Link
            to="/"
            className="inline-flex items-center justify-center gap-2 px-6 py-3 text-base font-semibold text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors"
          >
            <Home className="w-5 h-5" />
            {t('goHome') || 'Go Home'}
          </Link>

          <div className="flex items-center justify-center gap-4 text-sm text-text-secondary">
            <button
              onClick={() => window.history.back()}
              className="inline-flex items-center gap-1 text-text-secondary hover:text-text transition-colors"
            >
              <ArrowLeft className="w-4 h-4" />
              {t('goBack') || 'Go Back'}
            </button>
            <button
              onClick={() => window.location.reload()}
              className="inline-flex items-center gap-1 text-text-secondary hover:text-text transition-colors"
            >
              <RotateCcw className="w-4 h-4" />
              {t('refresh') || 'Refresh'}
            </button>
          </div>
        </div>

        <div className="mt-12 p-6 bg-surface rounded-2xl border border-border">
          <h3 className="font-semibold text-text mb-4">{t('quickLinks') || 'Quick Links'}</h3>
          <div className="grid grid-cols-2 gap-3">
            <Link to="/services" className="p-3 text-center bg-bg rounded-xl border border-border hover:border-primary/50 hover:bg-primary/5 transition-all text-sm text-text-secondary hover:text-text">
              {t('services') || 'Services'}
            </Link>
            <Link to="/fee-calculator" className="p-3 text-center bg-bg rounded-xl border border-border hover:border-primary/50 hover:bg-primary/5 transition-all text-sm text-text-secondary hover:text-text">
              {t('feeCalculator') || 'Fee Calculator'}
            </Link>
            <Link to="/centre-finder" className="p-3 text-center bg-bg rounded-xl border border-border hover:border-primary/50 hover:bg-primary/5 transition-all text-sm text-text-secondary hover:text-text">
              {t('centreFinder') || 'Centre Finder'}
            </Link>
            <Link to="/certificate-verify" className="p-3 text-center bg-bg rounded-xl border border-border hover:border-primary/50 hover:bg-primary/5 transition-all text-sm text-text-secondary hover:text-text">
              {t('certificateVerification') || 'Verify Certificate'}
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}