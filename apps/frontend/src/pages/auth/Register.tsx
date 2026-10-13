import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { Eye, EyeOff, Shield, AlertCircle, Loader2, Shield as ShieldIcon, CheckCircle, ArrowRight } from 'lucide-react';

interface FormData {
  username: string;
  email: string;
  password: string;
  confirmPassword: string;
  fullNameEn: string;
  fullNameAr?: string;
  mobile: string;
  nationalId?: string;
  terms: boolean;
}

interface FieldError {
  field: string;
  message: string;
}

export function Register() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { registerVisitor } = useAuth();
  const [formData, setFormData] = useState<FormData>({
    username: '',
    email: '',
    password: '',
    confirmPassword: '',
    fullNameEn: '',
    fullNameAr: '',
    mobile: '',
    nationalId: '',
    terms: false,
  });
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [error, setError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<FieldError[]>([]);
  const [step, setStep] = useState(1);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { isLoading: authIsLoading } = useAuth();
  const isLoading = isSubmitting || authIsLoading;
  const isRTL = document.documentElement.dir === 'rtl';

  const validateStep = (): boolean => {
    const errors: FieldError[] = [];

    if (step === 1) {
      if (!formData.fullNameEn.trim()) {
        errors.push({ field: 'fullNameEn', message: t('fullNameEnRequired') || 'Full name (English) is required' });
      }
      if (!formData.email.trim()) {
        errors.push({ field: 'email', message: t('emailRequired') || 'Email is required' });
      } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email)) {
        errors.push({ field: 'email', message: t('emailInvalid') || 'Invalid email format' });
      }
      if (!formData.mobile.trim()) {
        errors.push({ field: 'mobile', message: t('mobileRequired') || 'Mobile number is required' });
      } else if (!/^(\+971|0)?[0-9]{9}$/.test(formData.mobile.replace(/\s/g, ''))) {
        errors.push({ field: 'mobile', message: t('mobileInvalid') || 'Invalid UAE mobile number' });
      }
      if (formData.nationalId && !/^\d{15}$/.test(formData.nationalId)) {
        errors.push({ field: 'nationalId', message: t('nationalIdInvalid') || 'National ID must be 15 digits' });
      }
    } else if (step === 2) {
      if (!formData.username.trim()) {
        errors.push({ field: 'username', message: t('usernameRequired') || 'Username is required' });
      } else if (formData.username.length < 4) {
        errors.push({ field: 'username', message: t('usernameMinLength') || 'Username must be at least 4 characters' });
      }
      if (!formData.password) {
        errors.push({ field: 'password', message: t('passwordRequired') || 'Password is required' });
      } else if (formData.password.length < 8) {
        errors.push({ field: 'password', message: t('passwordMinLength') || 'Password must be at least 8 characters' });
      } else if (!/[A-Z]/.test(formData.password)) {
        errors.push({ field: 'password', message: t('passwordUppercase') || 'Password must contain at least one uppercase letter' });
      } else if (!/[0-9]/.test(formData.password)) {
        errors.push({ field: 'password', message: t('passwordNumber') || 'Password must contain at least one number' });
      } else if (!/[!@#$%^&*]/.test(formData.password)) {
        errors.push({ field: 'password', message: t('passwordSpecial') || 'Password must contain at least one special character' });
      }
      if (formData.password !== formData.confirmPassword) {
        errors.push({ field: 'confirmPassword', message: t('passwordsMismatch') || 'Passwords do not match' });
      }
    } else if (step === 3) {
      if (!formData.terms) {
        errors.push({ field: 'terms', message: t('termsRequired') || 'You must accept the terms and conditions' });
      }
    }

    setFieldErrors(errors);
    return errors.length === 0;
  };

  const handleChange = (field: keyof FormData, value: any) => {
    setFormData(prev => ({ ...prev, [field]: value }));
    setFieldErrors(prev => prev.filter(e => e.field !== field));
    setError('');
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateStep()) return;

    if (step < 3) {
      setStep(prev => prev + 1);
      return;
    }

    setIsSubmitting(true);
    setError('');

    try {
      await registerVisitor({
        passportNumber: formData.username,
        passportCountry: 'ARE',
        givenNameEn: formData.fullNameEn,
        familyNameEn: '',
        givenNameAr: formData.fullNameAr,
        familyNameAr: '',
        nationality: 'ARE',
        birthDate: '1990-01-01',
        gender: 'MALE',
        mobile: formData.mobile,
        email: formData.email,
      });

      navigate('/login?registered=true');
    } catch (err: any) {
      setError(err.message || t('registrationFailed') || 'Registration failed. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const getFieldError = (field: string) => {
    return fieldErrors.find(e => e.field === field)?.message;
  };

  const steps = [
    { number: 1, label: t('personalInfo') || 'Personal Info', href: '#' },
    { number: 2, label: t('accountDetails') || 'Account Details', href: '#' },
    { number: 3, label: t('termsConditions') || 'Terms & Conditions', href: '#' },
  ];

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg px-4 sm:px-6 lg:px-8 py-12">
      <div className="w-full max-w-2xl">
        {/* Header */}
        <div className="text-center mb-8">
          <Link to="/" className="inline-flex items-center gap-2 mb-6" aria-label="ITMS Home">
            <div className="w-10 h-10 rounded-lg bg-primary flex items-center justify-center">
              <span className="text-white font-bold text-lg">ITMS</span>
            </div>
            <span className="font-bold text-xl text-text">ITMS</span>
          </Link>
          <h1 className="text-2xl font-bold text-text mb-2">{t('register') || 'Create Account'}</h1>
          <p className="text-text-secondary">{t('registerSubtitle') || 'Register as a visitor to access UAE traffic services'}</p>
        </div>

        {/* Progress Steps */}
        <div className="mb-8" role="navigation" aria-label="Registration progress">
          <div className="flex items-center justify-between">
            {steps.map((s, idx) => (
              <div key={s.number} className="flex flex-col items-center">
                <div className={`relative flex items-center justify-center w-10 h-10 rounded-full font-semibold text-sm transition-all ${
                  idx + 1 < step ? 'bg-primary text-white' :
                  idx + 1 === step ? 'bg-primary text-white ring-4 ring-primary/20' :
                  'bg-surface border-2 border-border text-text-muted'
                }`}>
                  {idx + 1 < step ? <CheckCircle className="w-5 h-5" /> : s.number}
                </div>
                <span className="mt-2 text-xs text-center text-text-secondary max-w-[80px]">{s.label}</span>
                {idx < steps.length - 1 && (
                  <div className={`absolute top-5 left-1/2 w-full h-1 ${isRTL ? 'right-1/2' : 'left-1/2'} -translate-x-1/2 ${idx + 1 < step ? 'bg-primary' : 'bg-border'}`} />
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Error Message */}
        {error && (
          <div className="mb-6 p-4 bg-error/10 border border-error/20 rounded-xl text-error text-sm flex items-center gap-2" role="alert">
            <AlertCircle className="w-5 h-5 flex-shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {/* Registration Form */}
        <form onSubmit={handleSubmit} className="space-y-5" noValidate>
          {/* Step 1: Personal Information */}
          {step === 1 && (
            <fieldset>
              <legend className="text-lg font-semibold text-text mb-4 flex items-center gap-2">
                <ShieldIcon className="w-5 h-5 text-primary" />
                {t('personalInfo') || 'Personal Information'}
              </legend>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label htmlFor="fullNameEn" className="block text-sm font-medium text-text-secondary mb-1.5">
                    {t('fullNameEn') || 'Full Name (English)'} <span className="text-error" aria-hidden="true">*</span>
                  </label>
                  <input
                    type="text"
                    id="fullNameEn"
                    autoComplete="name"
                    value={formData.fullNameEn}
                    onChange={(e) => handleChange('fullNameEn', e.target.value)}
                    className={`w-full px-4 py-3 bg-surface border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all ${
                      getFieldError('fullNameEn') ? 'border-error' : 'border-border'
                    }`}
                    placeholder={t('fullNameEnPlaceholder') || 'John Smith'}
                    disabled={isLoading}
                    aria-required="true"
                    aria-invalid={!!getFieldError('fullNameEn')}
                    aria-describedby={getFieldError('fullNameEn') ? 'fullNameEn-error' : undefined}
                  />
                  {getFieldError('fullNameEn') && (
                    <p id="fullNameEn-error" className="mt-1.5 text-sm text-error" role="alert">{getFieldError('fullNameEn')}</p>
                  )}
                </div>

                <div>
                  <label htmlFor="fullNameAr" className="block text-sm font-medium text-text-secondary mb-1.5">
                    {t('fullNameAr') || 'Full Name (Arabic)'}
                  </label>
                  <input
                    type="text"
                    id="fullNameAr"
                    autoComplete="name"
                    value={formData.fullNameAr}
                    onChange={(e) => handleChange('fullNameAr', e.target.value)}
                    className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                    placeholder={t('fullNameArPlaceholder') || 'جون سميث'}
                    dir="rtl"
                    disabled={isLoading}
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label htmlFor="email" className="block text-sm font-medium text-text-secondary mb-1.5">
                    {t('email')} <span className="text-error" aria-hidden="true">*</span>
                  </label>
                  <input
                    type="email"
                    id="email"
                    autoComplete="email"
                    value={formData.email}
                    onChange={(e) => handleChange('email', e.target.value)}
                    className={`w-full px-4 py-3 bg-surface border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all ${
                      getFieldError('email') ? 'border-error' : 'border-border'
                    }`}
                    placeholder="john@example.com"
                    disabled={isLoading}
                    aria-required="true"
                    aria-invalid={!!getFieldError('email')}
                    aria-describedby={getFieldError('email') ? 'email-error' : undefined}
                  />
                  {getFieldError('email') && (
                    <p id="email-error" className="mt-1.5 text-sm text-error" role="alert">{getFieldError('email')}</p>
                  )}
                </div>

                <div>
                  <label htmlFor="mobile" className="block text-sm font-medium text-text-secondary mb-1.5">
                    {t('mobile')} <span className="text-error" aria-hidden="true">*</span>
                  </label>
                  <input
                    type="tel"
                    id="mobile"
                    autoComplete="tel"
                    value={formData.mobile}
                    onChange={(e) => handleChange('mobile', e.target.value.replace(/\D/g, '').slice(0, 10))}
                    className={`w-full px-4 py-3 bg-surface border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all ${
                      getFieldError('mobile') ? 'border-error' : 'border-border'
                    }`}
                    placeholder="0501234567"
                    disabled={isLoading}
                    aria-required="true"
                    aria-invalid={!!getFieldError('mobile')}
                    aria-describedby={getFieldError('mobile') ? 'mobile-error' : undefined}
                  />
                  {getFieldError('mobile') && (
                    <p id="mobile-error" className="mt-1.5 text-sm text-error" role="alert">{getFieldError('mobile')}</p>
                  )}
                </div>
              </div>

              <div>
                <label htmlFor="nationalId" className="block text-sm font-medium text-text-secondary mb-1.5">
                  {t('nationalId') || 'National ID (Optional)'}
                </label>
                <input
                  type="text"
                  id="nationalId"
                  autoComplete="off"
                  value={formData.nationalId}
                  onChange={(e) => handleChange('nationalId', e.target.value.replace(/\D/g, '').slice(0, 15))}
                  className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                  placeholder="784-1990-1234567-1"
                  disabled={isLoading}
                  maxLength={18}
                />
                <p className="mt-1.5 text-xs text-text-muted">{t('nationalIdHint') || '15-digit UAE National ID (optional for visitors)'}</p>
              </div>
            </fieldset>
          )}

          {/* Step 2: Account Details */}
          {step === 2 && (
            <fieldset>
              <legend className="text-lg font-semibold text-text mb-4 flex items-center gap-2">
                <Shield className="w-5 h-5 text-primary" />
                {t('accountDetails') || 'Account Details'}
              </legend>

              <div>
                <label htmlFor="username" className="block text-sm font-medium text-text-secondary mb-1.5">
                  {t('username')} <span className="text-error" aria-hidden="true">*</span>
                </label>
                <input
                  type="text"
                  id="username"
                  autoComplete="username"
                  value={formData.username}
                  onChange={(e) => handleChange('username', e.target.value)}
                  className={`w-full px-4 py-3 bg-surface border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all ${
                    getFieldError('username') ? 'border-error' : 'border-border'
                  }`}
                  placeholder={t('usernamePlaceholder') || 'Choose a username'}
                  disabled={isLoading}
                  aria-required="true"
                  aria-invalid={!!getFieldError('username')}
                  aria-describedby={getFieldError('username') ? 'username-error' : undefined}
                />
                {getFieldError('username') && (
                  <p id="username-error" className="mt-1.5 text-sm text-error" role="alert">{getFieldError('username')}</p>
                )}
              </div>

              <div>
                <label htmlFor="password" className="block text-sm font-medium text-text-secondary mb-1.5">
                  {t('password')} <span className="text-error" aria-hidden="true">*</span>
                </label>
                <div className="relative">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    id="password"
                    autoComplete="new-password"
                    value={formData.password}
                    onChange={(e) => handleChange('password', e.target.value)}
                    className={`w-full px-4 py-3 pr-11 bg-surface border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all ${
                      getFieldError('password') ? 'border-error' : 'border-border'
                    }`}
                    placeholder={t('passwordPlaceholder') || 'Create a strong password'}
                    disabled={isLoading}
                    aria-required="true"
                    aria-invalid={!!getFieldError('password')}
                    aria-describedby={getFieldError('password') ? 'password-error' : 'password-hint'}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-text-muted hover:text-text transition-colors"
                    aria-label={showPassword ? t('hidePassword') : t('showPassword')}
                  >
                    {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                  </button>
                </div>
                {getFieldError('password') ? (
                  <p id="password-error" className="mt-1.5 text-sm text-error" role="alert">{getFieldError('password')}</p>
                ) : (
                  <p id="password-hint" className="mt-1.5 text-xs text-text-muted">{t('passwordHint') || 'At least 8 characters, 1 uppercase, 1 number, 1 special character'}</p>
                )}
              </div>

              <div>
                <label htmlFor="confirmPassword" className="block text-sm font-medium text-text-secondary mb-1.5">
                  {t('confirmPassword')} <span className="text-error" aria-hidden="true">*</span>
                </label>
                <div className="relative">
                  <input
                    type={showConfirmPassword ? 'text' : 'password'}
                    id="confirmPassword"
                    autoComplete="new-password"
                    value={formData.confirmPassword}
                    onChange={(e) => handleChange('confirmPassword', e.target.value)}
                    className={`w-full px-4 py-3 pr-11 bg-surface border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all ${
                      getFieldError('confirmPassword') ? 'border-error' : 'border-border'
                    }`}
                    placeholder={t('confirmPasswordPlaceholder') || 'Confirm your password'}
                    disabled={isLoading}
                    aria-required="true"
                    aria-invalid={!!getFieldError('confirmPassword')}
                    aria-describedby={getFieldError('confirmPassword') ? 'confirmPassword-error' : undefined}
                  />
                  <button
                    type="button"
                    onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-text-muted hover:text-text transition-colors"
                    aria-label={showConfirmPassword ? t('hidePassword') : t('showPassword')}
                  >
                    {showConfirmPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                  </button>
                </div>
                {getFieldError('confirmPassword') && (
                  <p id="confirmPassword-error" className="mt-1.5 text-sm text-error" role="alert">{getFieldError('confirmPassword')}</p>
                )}
              </div>

              <div className="p-4 bg-primary/5 border border-primary/20 rounded-xl">
                <h4 className="font-medium text-primary mb-2">{t('passwordRequirements') || 'Password Requirements'}</h4>
                <ul className="space-y-1 text-xs text-text-secondary">
                  <li>• {t('reqMinLength') || 'At least 8 characters'}</li>
                  <li>• {t('reqUppercase') || 'At least one uppercase letter (A-Z)'}</li>
                  <li>• {t('reqNumber') || 'At least one number (0-9)'}</li>
                  <li>• {t('reqSpecial') || 'At least one special character (!@#$%^&*)'}</li>
                </ul>
              </div>
            </fieldset>
          )}

          {/* Step 3: Terms & Conditions */}
          {step === 3 && (
            <fieldset>
              <legend className="text-lg font-semibold text-text mb-4 flex items-center gap-2">
                <ShieldIcon className="w-5 h-5 text-primary" />
                {t('termsConditions') || 'Terms & Conditions'}
              </legend>

              <div className="space-y-4">
                <div className="p-4 bg-surface border border-border rounded-xl">
                  <label className="flex items-start gap-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formData.terms}
                      onChange={(e) => handleChange('terms', e.target.checked)}
                      className="w-5 h-5 mt-0.5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20"
                      disabled={isLoading}
                      aria-required="true"
                      aria-invalid={!!getFieldError('terms')}
                    />
                    <div className="text-sm text-text-secondary leading-relaxed">
                      <p className="font-medium text-text mb-2">{t('termsAccept') || 'I accept the Terms and Conditions'}</p>
                      <p className="text-text-muted text-xs">{t('termsText') || 'By creating an account, you agree to the ITMS Terms of Service, Privacy Policy, and consent to the processing of your personal data in accordance with UAE data protection laws. You confirm that the information provided is accurate and complete.'}</p>
                    </div>
                  </label>
                  {getFieldError('terms') && (
                    <p className="mt-1.5 text-sm text-error" role="alert">{getFieldError('terms')}</p>
                  )}
                </div>

                <div className="p-4 bg-surface border border-border rounded-xl">
                  <label className="flex items-start gap-3 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={false}
                      onChange={() => {}}
                      className="w-5 h-5 mt-0.5 rounded border-border text-primary focus:ring-2 focus:ring-primary/20"
                    />
                    <div className="text-sm text-text-secondary">
                      <p className="font-medium text-text mb-1">{t('marketingOptIn') || 'Receive updates and promotional offers'}</p>
                      <p className="text-text-muted text-xs">{t('marketingOptInDesc') || 'We\'ll send you service updates, fee changes, and occasional promotional offers. You can unsubscribe anytime.'}</p>
                    </div>
                  </label>
                </div>

                <Link
                  to="/terms"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm text-primary hover:underline flex items-center justify-center gap-1"
                >
                  <span>{t('viewFullTerms') || 'View full Terms of Service'}</span>
                  <ArrowRight className="w-4 h-4" />
                </Link>
                <Link
                  to="/privacy"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm text-primary hover:underline flex items-center justify-center gap-1"
                >
                  <span>{t('viewPrivacyPolicy') || 'View Privacy Policy'}</span>
                  <ArrowRight className="w-4 h-4" />
                </Link>
              </div>
            </fieldset>
          )}

          {/* Error Message */}
          {error && (
            <div className="p-4 bg-error/10 border border-error/20 rounded-xl text-error text-sm flex items-center gap-2" role="alert">
              <AlertCircle className="w-5 h-5 flex-shrink-0" />
              <span>{error}</span>
            </div>
          )}

          {/* Navigation Buttons */}
          <div className="flex flex-col sm:flex-row gap-3 pt-4">
            {step > 1 && (
              <button
                type="button"
                onClick={() => setStep(prev => prev - 1)}
                disabled={isLoading}
                className="flex-1 sm:flex-none px-6 py-3 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 hover:text-text transition-all disabled:opacity-50"
              >
                {t('back') || 'Back'}
              </button>
            )}

            {step < 3 ? (
              <button
                type="button"
                onClick={() => { if (validateStep()) setStep(prev => prev + 1); }}
                disabled={isLoading}
                className="flex-1 sm:flex-none px-6 py-3 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark focus:outline-none focus:ring-2 focus:ring-primary/50 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
              >
                {t('next') || 'Next'}
                <ArrowRight className="w-4 h-4 ml-2" />
              </button>
            ) : (
              <button
                type="submit"
                disabled={isLoading}
                className="flex-1 sm:flex-none px-6 py-3.5 text-base font-semibold text-white bg-primary rounded-xl hover:bg-primary-dark focus:outline-none focus:ring-2 focus:ring-primary/50 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed transition-all"
              >
                {isLoading ? (
                  <>
                    <Loader2 className="w-5 h-5 animate-spin mr-2" aria-hidden="true" />
                    {t('creatingAccount') || 'Creating account...'}
                  </>
                ) : (
                  <>
                    {t('createAccount') || 'Create Account'}
                    <ArrowRight className="w-4 h-5 ml-2" />
                  </>
                )}
              </button>
            )}
          </div>
        </form>

        {/* Footer */}
        <div className="mt-8 text-center space-y-3 text-sm">
          <p className="text-text-secondary">
            {t('alreadyHaveAccount') || 'Already have an account?'}
            <Link to="/login" className="text-primary hover:underline font-medium ml-1">
              {t('signIn') || 'Sign in'}
            </Link>
          </p>
          <p className="text-text-muted text-xs max-w-md mx-auto">
            {t('registerDisclaimer') || 'By registering, you agree to our Terms of Service and Privacy Policy. Your data is protected under UAE Federal Law No. 45 of 2021 on Personal Data Protection.'}
          </p>
        </div>
      </div>
    </div>
  );
}