import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Car, FileText, AlertTriangle, CreditCard, Calendar, FileCheck, Search, Filter, Plus, Truck, Building2 } from 'lucide-react';

export function PortalServices() {
  const { t } = useTranslation();

  const serviceCategories = [
    {
      title: t('vehicles') || 'Vehicles',
      description: t('vehiclesDesc') || 'Registration, renewal, ownership transfer, and more',
      icon: Car,
      color: 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400',
      services: [
        { name: t('newRegistration') || 'New Registration', desc: t('newRegistrationDesc') || 'Register a new vehicle' },
        { name: t('renewRegistration') || 'Renew Registration', desc: t('renewRegistrationDesc') || 'Renew your vehicle registration' },
        { name: t('transferOwnership') || 'Transfer Ownership', desc: t('transferOwnershipDesc') || 'Transfer vehicle to new owner' },
        { name: t('replacePlate') || 'Replace Plate', desc: t('replacePlateDesc') || 'Lost or damaged plate replacement' },
        { name: t('exportVehicle') || 'Export Vehicle', desc: t('exportVehicleDesc') || 'Export certificate for vehicle' },
        { name: t('temporaryPermit') || 'Temporary Permit', desc: t('temporaryPermitDesc') || 'Entry/exit permits for visitors' },
      ],
      href: '/portal/services/vehicles',
    },
    {
      title: t('licences') || 'Licences',
      description: t('licencesDesc') || 'Issue, renew, replace, and upgrade driving licences',
      icon: FileText,
      color: 'bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400',
      services: [
        { name: t('issueLicence') || 'Issue New Licence', desc: t('issueLicenceDesc') || 'Apply for first driving licence' },
        { name: t('renewLicence') || 'Renew Licence', desc: t('renewLicenceDesc') || 'Renew your driving licence' },
        { name: t('replaceLicence') || 'Replace Licence', desc: t('replaceLicenceDesc') || 'Lost or damaged licence replacement' },
        { name: t('upgradeLicence') || 'Upgrade Licence', desc: t('upgradeLicenceDesc') || 'Add new vehicle class' },
        { name: t('bookTests') || 'Book Tests', desc: t('bookTestsDesc') || 'Theory, practical, and eye tests' },
      ],
      href: '/portal/services/licences',
    },
    {
      title: t('violations') || 'Violations',
      description: t('violationsDesc') || 'View, pay, and object to traffic violations',
      icon: AlertTriangle,
      color: 'bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400',
      services: [
        { name: t('payViolation') || 'Pay Violation', desc: t('payViolationDesc') || 'Pay fines online securely' },
        { name: t('fileObjection') || 'File Objection', desc: t('fileObjectionDesc') || 'Object to a violation' },
        { name: t('instalmentPlan') || 'Instalment Plan', desc: t('instalmentPlanDesc') || 'Pay fines in instalments' },
        { name: t('trafficSchool') || 'Traffic School', desc: t('trafficSchoolDesc') || 'Reduce points with traffic school' },
        { name: t('violationHistory') || 'Violation History', desc: t('violationHistoryDesc') || 'View all your violations' },
      ],
      href: '/portal/services/violations',
    },
    {
      title: t('payments') || 'Payments',
      description: t('paymentsDesc') || 'Manage all your payments and transactions',
      icon: CreditCard,
      color: 'bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400',
      services: [
        { name: t('makePayment') || 'Make Payment', desc: t('makePaymentDesc') || 'Pay for any service' },
        { name: t('paymentHistory') || 'Payment History', desc: t('paymentHistoryDesc') || 'View all transactions' },
        { name: t('downloadReceipts') || 'Download Receipts', desc: t('downloadReceiptsDesc') || 'Get official receipts' },
        { name: t('refundRequest') || 'Request Refund', desc: t('refundRequestDesc') || 'Request refund for overpayment' },
      ],
      href: '/portal/services/payments',
    },
    {
      title: t('appointments') || 'Appointments',
      description: t('appointmentsDesc') || 'Book and manage service centre appointments',
      icon: Calendar,
      color: 'bg-orange-50 text-orange-600 dark:bg-orange-900/20 dark:text-orange-400',
      services: [
        { name: t('bookAppointment') || 'Book Appointment', desc: t('bookAppointmentDesc') || 'Schedule a service centre visit' },
        { name: t('manageAppointments') || 'Manage Appointments', desc: t('manageAppointmentsDesc') || 'View, reschedule, or cancel' },
        { name: t('queueStatus') || 'Queue Status', desc: t('queueStatusDesc') || 'Live queue tracking' },
      ],
      href: '/portal/services/appointments',
    },
    {
      title: t('documents') || 'Documents',
      description: t('documentsDesc') || 'Upload, manage, and verify documents',
      icon: FileCheck,
      color: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/20 dark:text-indigo-400',
      services: [
        { name: t('uploadDocument') || 'Upload Document', desc: t('uploadDocumentDesc') || 'Submit required documents' },
        { name: t('manageDocuments') || 'Manage Documents', desc: t('manageDocumentsDesc') || 'View and organise your documents' },
        { name: t('verifyCertificate') || 'Verify Certificate', desc: t('verifyCertificateDesc') || 'Check certificate authenticity' },
        { name: t('downloadCertificates') || 'Download Certificates', desc: t('downloadCertificatesDesc') || 'Get digital copies' },
      ],
      href: '/portal/services/documents',
    },
  ];

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-text mb-2">{t('services') || 'All Services'}</h1>
        <p className="text-text-secondary">{t('servicesSubtitle') || 'Browse all available traffic services. Filter by category or search for what you need.'}</p>
      </div>

      {/* Search & Filter */}
      <div className="flex flex-col sm:flex-row gap-4 mb-8">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" aria-hidden="true" />
          <input
            type="search"
            placeholder={t('searchServices') || 'Search services...'}
            className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
          />
        </div>
        <select className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByCategory') || 'Filter by category'}>
          <option value="all">{t('allCategories') || 'All Categories'}</option>
          <option value="vehicles">{t('vehicles') || 'Vehicles'}</option>
          <option value="licences">{t('licences') || 'Licences'}</option>
          <option value="violations">{t('violations') || 'Violations'}</option>
          <option value="payments">{t('payments') || 'Payments'}</option>
          <option value="appointments">{t('appointments') || 'Appointments'}</option>
          <option value="documents">{t('documents') || 'Documents'}</option>
        </select>
      </div>

      {/* Service Categories */}
      <div className="space-y-8">
        {serviceCategories.map((category, catIndex) => (
          <section key={catIndex} className="bg-surface rounded-2xl border border-border overflow-hidden">
            <div className="p-6 border-b border-border flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <div className="flex items-center gap-4">
                <div className={`p-3 rounded-xl ${category.color}`}>
                  <category.icon className="w-6 h-6" aria-hidden="true" />
                </div>
                <div>
                  <h2 className="text-xl font-semibold text-text">{category.title}</h2>
                  <p className="text-text-secondary">{category.description}</p>
                </div>
              </div>
              <Link
                to={category.href}
                className="inline-flex items-center gap-1 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors"
              >
                {t('viewAll') || 'View all'}
                <Plus className="w-4 h-4" />
              </Link>
            </div>

            <div className="p-6">
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {category.services.map((service, svcIndex) => (
                  <Link
                    key={svcIndex}
                    to={`${category.href}/${service.name.toLowerCase().replace(/\s+/g, '-')}`}
                    className="group p-4 bg-bg rounded-xl border border-border hover:border-primary/50 hover:shadow-md hover:bg-primary/5 transition-all duration-300"
                  >
                    <h3 className="font-medium text-text group-hover:text-primary transition-colors">{service.name}</h3>
                    <p className="text-sm text-text-secondary mt-1">{service.desc}</p>
                    <div className="mt-3 inline-flex items-center gap-1 text-primary text-sm font-medium group-hover:gap-2 transition-all">
                      {t('start') || 'Start'}
                      <Plus className="w-4 h-4" />
                    </div>
                  </Link>
                ))}
              </div>
            </div>
          </section>
        ))}
      </div>

      {/* CTA */}
      <div className="text-center p-8 bg-primary/5 rounded-2xl border border-primary/20">
        <h3 className="text-xl font-semibold text-primary mb-2">{t('cantFindService') || 'Can\'t find what you\'re looking for?'}</h3>
        <p className="text-primary/80 mb-4">{t('cantFindServiceDesc') || 'Contact our support team for assistance with any service.'}</p>
        <Link
          to="/contact"
          className="inline-flex items-center gap-2 px-6 py-3 text-base font-semibold text-primary bg-white rounded-xl hover:bg-primary/10 transition-colors"
        >
          {t('contactSupport') || 'Contact Support'}
          <Plus className="w-4 h-4" />
        </Link>
      </div>
    </div>
  );
}