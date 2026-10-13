import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Shield,
  Clock,
  Smartphone,
  Globe,
  CreditCard,
  Calendar,
  Search,
  ArrowRight,
  CheckCircle,
  Star,
  Truck,
  Building2,
  Car,
  FileText,
  AlertTriangle,
} from 'lucide-react';

export function PublicHome() {
  const { t } = useTranslation();

  const features = [
    {
      icon: Shield,
      title: t('secure') || 'Secure & Trusted',
      description: t('secureDesc') || 'National digital identity integration with end-to-end encryption and audit trails.',
    },
    {
      icon: Clock,
      title: t('available247') || 'Available 24/7',
      description: t('available247Desc') || 'Access services anytime, anywhere. No need to visit service centres during working hours.',
    },
    {
      icon: Smartphone,
      title: t('mobileFirst') || 'Mobile First',
      description: t('mobileFirstDesc') || 'Responsive design works seamlessly on phone, tablet, and desktop. Native app experience.',
    },
    {
      icon: Globe,
      title: t('bilingual') || 'Bilingual (EN/AR)',
      description: t('bilingualDesc') || 'Full Arabic and English support with RTL layout. Switch languages instantly.',
    },
    {
      icon: CreditCard,
      title: t('multiplePayments') || 'Multiple Payment Options',
      description: t('multiplePaymentsDesc') || 'Credit cards, bank transfers, digital wallets, and cash at service centres.',
    },
    {
      icon: Calendar,
      title: t('appointments') || 'Smart Appointments',
      description: t('appointmentsDesc') || 'Book, reschedule, and manage appointments with real-time queue updates.',
    },
  ];

  const services = [
    {
      icon: Car,
      title: t('vehicles') || 'Vehicles',
      description: t('vehiclesDesc') || 'Registration, renewal, ownership transfer, export, and temporary permits.',
      href: '/services',
      color: 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400',
    },
    {
      icon: FileText,
      title: t('licences') || 'Licences',
      description: t('licencesDesc') || 'Issue, renew, replace, upgrade driving licences. Book tests and medical exams.',
      href: '/services',
      color: 'bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400',
    },
    {
      icon: AlertTriangle,
      title: t('violations') || 'Violations',
      description: t('violationsDesc') || 'View, pay, object to violations. Instalment plans and traffic school options.',
      href: '/services',
      color: 'bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400',
    },
    {
      icon: CreditCard,
      title: t('payments') || 'Payments',
      description: t('paymentsDesc') || 'Secure payments for all services. Download receipts and track history.',
      href: '/services',
      color: 'bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400',
    },
    {
      icon: Calendar,
      title: t('appointments') || 'Appointments',
      description: t('appointmentsDesc') || 'Book service centre visits. Real-time queue tracking and reminders.',
      href: '/services',
      color: 'bg-orange-50 text-orange-600 dark:bg-orange-900/20 dark:text-orange-400',
    },
    {
      icon: FileText,
      title: t('documents') || 'Documents',
      description: t('documentsDesc') || 'Upload, manage, and verify documents. Digital certificates with QR codes.',
      href: '/services',
      color: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/20 dark:text-indigo-400',
    },
  ];

  const stats = [
    { value: '2M+', label: t('happyCustomers') || 'Happy Customers' },
    { value: '500K+', label: t('transactionsMonthly') || 'Transactions/Month' },
    { value: '99.9%', label: t('uptime') || 'System Uptime' },
    { value: '30s', label: t('avgResponse') || 'Avg Response Time' },
  ];

  const ctaItems = [
    {
      icon: Search,
      title: t('serviceGuide') || 'Service Guide',
      description: t('serviceGuideDesc') || 'Browse all available services with requirements, fees, and processing times.',
      href: '/services',
    },
    {
      icon: CreditCard,
      title: t('feeCalculator') || 'Fee Calculator',
      description: t('feeCalculatorDesc') || 'Calculate exact fees for any service based on your category and region.',
      href: '/fee-calculator',
    },
    {
      icon: Building2,
      title: t('centreFinder') || 'Centre Finder',
      description: t('centreFinderDesc') || 'Find the nearest service centre with hours, services, and live queue status.',
      href: '/centre-finder',
    },
    {
      icon: FileText,
      title: t('certificateVerification') || 'Verify Certificate',
      description: t('certificateVerifyDesc') || 'Instantly verify the authenticity of any digital certificate or licence.',
      href: '/certificate-verify',
    },
    {
      icon: AlertTriangle,
      title: t('violationEnquiry') || 'Violation Enquiry',
      description: t('violationEnquiryDesc') || 'Check violations by plate number. View details, photos, and payment options.',
      href: '/violation-enquiry',
    },
  ];

  return (
    <div className="min-h-screen">
      {/* Hero Section */}
      <section className="relative overflow-hidden bg-gradient-to-b from-primary/5 via-surface to-surface py-20 lg:py-32">
        <div className="absolute inset-0 bg-[url('/grid.svg')] opacity-5" aria-hidden="true" />
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative">
          <div className="max-w-3xl mx-auto text-center">
            <div className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-primary/10 text-primary text-sm font-medium mb-6">
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-primary opacity-75" />
                <span className="relative inline-flex rounded-full h-2 w-2 bg-primary" />
              </span>
              Now Available: Digital Licence & Registration Cards
            </div>
            <h1 className="text-4xl sm:text-5xl lg:text-6xl font-bold text-text tracking-tight mb-6">
              {t('heroTitle') || 'Your Traffic Services, Simplified'}
            </h1>
            <p className="text-lg sm:text-xl text-text-secondary max-w-2xl mx-auto mb-10 leading-relaxed">
              {t('heroSubtitle') || 'Access all traffic services online — licences, vehicles, violations, payments, and appointments. Secure, bilingual, and available 24/7.'}
            </p>
            <div className="flex flex-col sm:flex-row items-center justify-center gap-4 mb-16">
              <Link
                to="/login"
                className="group inline-flex items-center justify-center gap-2 px-8 py-4 text-base font-semibold text-white bg-primary rounded-xl hover:bg-primary-dark transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-primary/50 focus:ring-offset-2"
              >
                {t('signIn')}
                <ArrowRight className="w-5 h-5 transition-transform group-hover:translate-x-1" />
              </Link>
              <Link
                to="/register"
                className="inline-flex items-center justify-center gap-2 px-8 py-4 text-base font-semibold text-primary bg-primary/10 rounded-xl hover:bg-primary/20 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-primary/50 focus:ring-offset-2"
              >
                {t('register')}
              </Link>
            </div>
            <p className="text-sm text-text-muted">
              {t('noAccount') || 'Don\'t have an account?'} <Link to="/register" className="text-primary hover:underline font-medium">{t('createOne') || 'Create one'}</Link>
            </p>
          </div>

          {/* Trust indicators */}
          <div className="flex flex-wrap items-center justify-center gap-8 text-sm text-text-muted">
            <div className="flex items-center gap-2">
              <CheckCircle className="w-5 h-5 text-success" />
              <span>{t('securedByNationalId') || 'Secured by National Digital Identity'}</span>
            </div>
            <div className="flex items-center gap-2">
              <CheckCircle className="w-5 h-5 text-success" />
              <span>{t('wcagCompliant') || 'WCAG 2.2 AA Compliant'}</span>
            </div>
            <div className="flex items-center gap-2">
              <CheckCircle className="w-5 h-5 text-success" />
              <span>{t('onPremise') || 'Fully On-Premise, Data Never Leaves UAE'}</span>
            </div>
          </div>
        </div>
      </section>

      {/* Stats */}
      <section className="py-16 bg-surface border-y border-border">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-8">
            {stats.map((stat, index) => (
              <div key={index} className="text-center">
                <div className="text-3xl sm:text-4xl lg:text-5xl font-bold text-primary mb-2">{stat.value}</div>
                <div className="text-text-secondary">{stat.label}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Services */}
      <section className="py-24">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-16">
            <h2 className="text-3xl sm:text-4xl font-bold text-text mb-4">{t('ourServices') || 'All Traffic Services in One Place'}</h2>
            <p className="text-lg text-text-secondary max-w-2xl mx-auto">{t('servicesSubtitle') || 'From licence renewal to violation payment — everything you need, accessible from any device.'}</p>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {services.map((service, index) => (
              <Link
                key={index}
                to={service.href}
                className="group relative p-6 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-xl transition-all duration-300"
              >
                <div className={`w-12 h-12 rounded-xl flex items-center justify-center mb-4 ${service.color}`}>
                  <service.icon className="w-6 h-6" aria-hidden="true" />
                </div>
                <h3 className="text-xl font-semibold text-text mb-2 group-hover:text-primary transition-colors">{service.title}</h3>
                <p className="text-text-secondary mb-4">{service.description}</p>
                <div className="inline-flex items-center gap-1 text-primary font-medium text-sm group-hover:gap-2 transition-all">
                  {t('learnMore') || 'Learn more'}
                  <ArrowRight className="w-4 h-4 transition-transform group-hover:translate-x-1" />
                </div>
              </Link>
            ))}
          </div>
        </div>
      </section>

      {/* Quick Actions / CTAs */}
      <section className="py-24 bg-surface border-y border-border">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-16">
            <h2 className="text-3xl sm:text-4xl font-bold text-text mb-4">{t('quickActions') || 'Quick Actions — No Login Required'}</h2>
            <p className="text-lg text-text-secondary max-w-2xl mx-auto">{t('quickActionsDesc') || 'Access public services instantly without signing in.'}</p>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
            {ctaItems.map((item, index) => (
              <Link
                key={index}
                to={item.href}
                className="group p-6 bg-white/50 dark:bg-surface/50 rounded-2xl border border-border hover:border-primary/50 hover:shadow-xl transition-all duration-300"
              >
                <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mb-4 text-primary">
                  <item.icon className="w-6 h-6" aria-hidden="true" />
                </div>
                <h3 className="text-xl font-semibold text-text mb-2">{item.title}</h3>
                <p className="text-text-secondary mb-4">{item.description}</p>
                <div className="inline-flex items-center gap-1 text-primary font-medium text-sm group-hover:gap-2 transition-all">
                  {t('startNow') || 'Start now'}
                  <ArrowRight className="w-4 h-4 transition-transform group-hover:translate-x-1" />
                </div>
              </Link>
            ))}
          </div>
        </div>
      </section>

      {/* Features */}
      <section className="py-24">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-16">
            <h2 className="text-3xl sm:text-4xl font-bold text-text mb-4">{t('whyChooseITMS') || 'Why Choose ITMS?'}</h2>
            <p className="text-lg text-text-secondary max-w-2xl mx-auto">{t('whyChooseDesc') || 'Built for the UAE\'s traffic management needs with world-class standards.'}</p>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            {features.map((feature, index) => (
              <div key={index} className="p-6 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all duration-300">
                <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mb-4 text-primary">
                  <feature.icon className="w-6 h-6" aria-hidden="true" />
                </div>
                <h3 className="text-xl font-semibold text-text mb-2">{feature.title}</h3>
                <p className="text-text-secondary">{feature.description}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* CTA Section */}
      <section className="py-24 bg-primary text-white">
        <div className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 text-center">
          <h2 className="text-3xl sm:text-4xl font-bold mb-6">{t('readyToStart') || 'Ready to Get Started?'}</h2>
          <p className="text-lg text-primary/80 mb-10 max-w-2xl mx-auto">{t('readyDesc') || 'Join millions of users managing their traffic services online. Secure, fast, and convenient.'}</p>
          <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
            <Link
              to="/register"
              className="group inline-flex items-center justify-center gap-2 px-8 py-4 text-base font-semibold text-primary bg-white rounded-xl hover:bg-primary/10 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-white/50 focus:ring-offset-2 focus:ring-offset-primary"
            >
              {t('createFreeAccount') || 'Create Free Account'}
              <ArrowRight className="w-5 h-5 transition-transform group-hover:translate-x-1" />
            </Link>
            <Link
              to="/services"
              className="inline-flex items-center justify-center gap-2 px-8 py-4 text-base font-semibold text-primary bg-transparent border-2 border-white/30 rounded-xl hover:bg-white/10 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-white/50 focus:ring-offset-2 focus:ring-offset-primary"
            >
              {t('exploreServices') || 'Explore Services'}
            </Link>
          </div>
        </div>
      </section>
    </div>
  );
}