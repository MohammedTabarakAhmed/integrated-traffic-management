import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Search, AlertTriangle, CreditCard, Calendar, Clock, MapPin, Shield, CheckCircle, XCircle, AlertCircle, Eye, Download, Copy, Loader2, FileText, QrCode, Verified, Award, Badge, User, Calendar as CalendarIcon, Clock as ClockIcon, MapPin as MapPinIcon, Navigation, Car, DollarSign, Gavel, Scale, FileCheck, Star, Award as AwardIcon, Trophy, Medal, Crown, Gem, Diamond, Sparkles, MagicWand, Wand, Target, Flag, Filter, FilterX, Home, Building2, Car as CarIcon, FileText, AlertTriangle as AlertTriangleIcon, CreditCard as CreditCardIcon, Calendar as CalendarIcon2, Clock as ClockIcon2, MapPin as MapPinIcon2, Navigation, Car as CarIcon2, FileText as FileTextIcon2, AlertTriangle as AlertTriangleIcon2, CreditCard as CreditCardIcon2, Calendar as CalendarIcon3, Clock as ClockIcon3, MapPin as MapPinIcon3, Navigation as NavigationIcon2 } from 'lucide-react';
import { Link } from 'react-router-dom';

export function PublicViolationEnquiry() {
  const { t } = useTranslation();
  const [searchType, setSearchType] = useState<'plate' | 'violation' | 'licence'>('plate');
  const [searchValue, setSearchValue] = useState('');
  const [result, setResult] = useState<{
    found: boolean;
    violations?: Array<{
      id: string;
      number: string;
      plate: string;
      date: string;
      time: string;
      location: string;
      type: string;
      fine: number;
      points: number;
      status: string;
      dueDate: string;
      paidDate?: string;
      objectionStatus?: string;
      imageUrl?: string;
    }>;
    error?: string;
  } | null>(null);
  const [loading, setLoading] = useState(false);

  const search = async () => {
    if (!searchValue.trim()) return;
    
    setLoading(true);
    setResult(null);
    
    await new Promise(resolve => setTimeout(resolve, 1200));
    
    // Mock results
    if (searchType === 'plate' && searchValue.length >= 3) {
      setResult({
        found: true,
        violations: [
          { id: '1', number: 'V-2024-001234', plate: searchValue, date: '2024-11-15', time: '14:30', location: 'Sheikh Zayed Road, Dubai', type: 'Speeding (80 in 60 zone)', fine: 600, points: 4, status: 'unpaid', dueDate: '2024-12-15', imageUrl: 'https://example.com/violation1.jpg' },
          { id: '2', number: 'V-2024-001235', plate: searchValue, date: '2024-10-20', time: '09:15', location: 'Al Khail Road, Dubai', type: 'Red Light Violation', fine: 1000, points: 8, status: 'paid', dueDate: '2024-11-19', paidDate: '2024-10-22' },
          { id: '3', number: 'V-2024-001236', plate: searchValue, date: '2024-11-01', time: '16:45', location: 'Emirates Road, Abu Dhabi', type: 'Illegal Parking', fine: 400, points: 2, status: 'objection_pending', dueDate: '2024-12-01', objectionStatus: 'under_review' },
        ],
      });
    } else if (searchType === 'violation' && searchValue.length >= 5) {
      setResult({
        found: true,
        violations: [
          { id: '1', number: searchValue, plate: 'A 12345', date: '2024-11-15', time: '14:30', location: 'Sheikh Zayed Road, Dubai', type: 'Speeding (80 in 60 zone)', fine: 600, points: 4, status: 'unpaid', dueDate: '2024-12-15', imageUrl: 'https://example.com/violation1.jpg' },
        ],
      });
    } else if (searchType === 'licence' && searchValue.length >= 5) {
      setResult({
        found: true,
        violations: [
          { id: '1', number: 'V-2024-001234', plate: 'A 12345', date: '2024-11-15', time: '14:30', location: 'Sheikh Zayed Road, Dubai', type: 'Speeding (80 in 60 zone)', fine: 600, points: 4, status: 'unpaid', dueDate: '2024-12-15' },
          { id: '2', number: 'V-2024-001235', plate: 'A 12345', date: '2024-10-20', time: '09:15', location: 'Al Khail Road, Dubai', type: 'Red Light Violation', fine: 1000, points: 8, status: 'paid', dueDate: '2024-11-19', paidDate: '2024-10-22' },
        ],
      });
    } else {
      setResult({ found: false, error: 'No violations found for the given search criteria' });
    }
    
    setLoading(false);
  };

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    search();
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-text mb-2">{t('violationEnquiry') || 'Violation Enquiry'}</h1>
        <p className="text-text-secondary">{t('violationEnquiryDesc') || 'Check traffic violations by plate number, violation number, or licence number'}</p>
      </div>

      {/* Search Form */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <h2 className="text-lg font-semibold text-text mb-6">{t('searchViolations') || 'Search Violations'}</h2>
        
        <form onSubmit={handleSearch} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-2">{t('searchBy') || 'Search By'}</label>
            <div className="flex gap-2 flex-wrap">
              {[
                { value: 'plate', label: 'Plate Number', icon: Car },
                { value: 'violation', label: 'Violation Number', icon: FileText },
                { value: 'licence', label: 'Licence Number', icon: User },
              ].map(option => (
                <button
                  key={option.value}
                  type="button"
                  onClick={() => setSearchType(option.value)}
                  className={`px-4 py-2 rounded-xl border-2 font-medium transition-all flex items-center gap-2 ${
                    searchType === option.value
                      ? 'bg-primary text-white border-primary'
                      : 'bg-surface border-border text-text-secondary hover:border-primary/50'
                  }`}
                >
                  <option.icon className="w-4 h-4" />
                  {option.label}
                </button>
              ))}
            </div>
          </div>

          <div className="flex gap-3">
            <div className="flex-1 relative">
              <input
                type="text"
                value={searchValue}
                onChange={(e) => setSearchValue(e.target.value.toUpperCase())}
                placeholder={searchType === 'plate' ? 'A 12345' : searchType === 'violation' ? 'V-2024-001234' : 'DL-2024-001234'}
                className="w-full px-4 py-3 pl-11 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary text-uppercase"
                maxLength={20}
              />
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
            </div>
            <button
              type="submit"
              disabled={loading || !searchValue.trim()}
              className="px-6 py-3 text-base font-semibold text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
            >
              {loading ? (
                <>
                  <Loader2 className="w-5 h-5 animate-spin" />
                  {t('searching') || 'Searching...'}
                </>
              ) : (
                <>
                  <Search className="w-5 h-5" />
                  {t('search') || 'Search'}
                </>
              )}
            </button>
          </div>
        </form>

        <p className="mt-4 text-sm text-text-muted">
          {searchType === 'plate' && t('plateSearchHint') || 'Enter plate number (e.g., A 12345, DXB 12345)'}
          {searchType === 'violation' && t('violationSearchHint') || 'Enter violation number (e.g., V-2024-001234)'}
          {searchType === 'licence' && t('licenceSearchHint') || 'Enter licence number (e.g., DL-2024-001234)'}
        </p>
      </div>

      {/* Result */}
      {result && (
        <div className={`bg-surface rounded-2xl border border-border p-6 ${result.found ? 'border-green-500/50 bg-green-50/50' : 'border-red-500/50 bg-red-50/50'}`}>
          <div className="flex items-start gap-4 mb-6">
            <div className={`p-3 rounded-xl ${result.found ? 'bg-green-500' : 'bg-red-500'}`}>
              {result.found ? (
                <CheckCircle className="w-6 h-6 text-white" />
              ) : (
                <XCircle className="w-6 h-6 text-white" />
              )}
            </div>
            <div className="flex-1">
              <h3 className={`text-lg font-semibold ${result.found ? 'text-green-700' : 'text-red-700'}`}>
                {result.found ? t('violationsFound') || 'Violations Found' : t('noViolationsFound') || 'No Violations Found'}
              </h3>
              {result.error && (
                <p className="text-red-600 mt-1">{result.error}</p>
              )}
              {result.found && result.violations && (
                <p className="text-text-secondary mt-1">{result.violations.length} {t('violationsFoundCount') || 'violation(s) found'}</p>
              )}
            </div>
          </div>

          {result.found && result.violations && (
            <div className="space-y-4">
              {result.violations.map((violation, index) => (
                <div key={index} className="bg-bg rounded-2xl border border-border p-5">
                  <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-4">
                    <div className="flex items-center gap-3">
                      <div className="p-3 rounded-xl bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400">
                        <AlertTriangle className="w-5 h-5" />
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <code className="text-sm font-mono text-text">{violation.number}</code>
                          <span className={`inline-flex items-center px-2 py-1 rounded-full text-xs font-medium ${
                            violation.status === 'unpaid' ? 'bg-red-50 text-red-700' :
                            violation.status === 'paid' ? 'bg-green-50 text-green-700' :
                            violation.status === 'objection_pending' ? 'bg-yellow-50 text-yellow-700' :
                            'bg-blue-50 text-blue-700'
                          }`}>
                            {violation.status}
                          </span>
                        </div>
                        <p className="text-sm text-text-secondary">{violation.type}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="px-3 py-1 text-xs font-medium text-white bg-primary rounded-full">
                        {violation.fine > 0 ? `AED ${violation.fine.toLocaleString()}` : t('noFine') || 'No Fine'}
                      </span>
                      <span className="px-3 py-1 text-xs font-medium text-white bg-purple-600 rounded-full">
                        {violation.points} {t('points') || 'pts'}
                      </span>
                    </div>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-4">
                    <div>
                      <p className="text-xs text-text-secondary">{t('date') || 'Date'}</p>
                      <p className="font-medium text-text">{new Date(violation.date).toLocaleDateString()}</p>
                    </div>
                    <div>
                      <p className="text-xs text-text-secondary">{t('time') || 'Time'}</p>
                      <p className="font-medium text-text">{violation.time}</p>
                    </div>
                    <div>
                      <p className="text-xs text-text-secondary">{t('location') || 'Location'}</p>
                      <p className="font-medium text-text truncate">{violation.location}</p>
                    </div>
                    <div>
                      <p className="text-xs text-text-secondary">{t('dueDate') || 'Due Date'}</p>
                      <p className="font-medium text-text">{new Date(violation.dueDate).toLocaleDateString()}</p>
                    </div>
                    {violation.paidDate && (
                      <div>
                        <p className="text-xs text-text-secondary">{t('paidDate') || 'Paid Date'}</p>
                        <p className="font-medium text-text">{new Date(violation.paidDate!).toLocaleDateString()}</p>
                      </div>
                    )}
                    {violation.objectionStatus && (
                      <div>
                        <p className="text-xs text-text-secondary">{t('objectionStatus') || 'Objection'}</p>
                        <p className="font-medium text-text capitalize">{violation.objectionStatus.replace('_', ' ')}</p>
                      </div>
                    )}
                  </div>

                  <div className="flex flex-wrap gap-2">
                    <Link to={`/portal/services/violations/pay/${violation.id}`} className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
                      <CreditCard className="w-4 h-4" />
                      {t('payNow') || 'Pay Now'}
                    </Link>
                    {violation.status !== 'paid' && (
                      <Link to={`/portal/services/violations/object/${violation.id}`} className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-primary bg-primary/10 rounded-xl hover:bg-primary/20 transition-colors">
                        <Gavel className="w-4 h-4" />
                        {t('fileObjection') || 'File Objection'}
                      </Link>
                    )}
                    <button className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
                      <Eye className="w-4 h-4 mr-2" />
                      {t('viewDetails') || 'Details'}
                    </button>
                    <button className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
                      <Download className="w-4 h-4 mr-2" />
                      {t('receipt') || 'Receipt'}
                    </button>
                  </div>

                  {violation.imageUrl && (
                    <div className="mt-4 pt-4 border-t border-border">
                      <p className="text-sm font-medium text-text-secondary mb-2">{t('violationImage') || 'Violation Image'}</p>
                      <div className="aspect-video bg-gray-100 dark:bg-gray-800 rounded-xl flex items-center justify-center">
                        <span className="text-text-muted">{t('imagePlaceholder') || 'Violation camera image would appear here'}</span>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {!result.found && result.error && (
            <div className="text-center py-8">
              <AlertCircle className="w-12 h-12 text-red-500 mx-auto mb-4" />
              <h3 className="text-lg font-semibold text-text mb-2">{t('noViolationsFound') || 'No Violations Found'}</h3>
              <p className="text-text-muted mb-4">{result.error}</p>
              <div className="flex justify-center gap-3">
                <button onClick={() => setSearchType('plate')} className="px-4 py-2 text-sm font-medium text-primary bg-primary/10 rounded-xl hover:bg-primary/20 transition-colors">
                  {t('tryPlateSearch') || 'Try Plate Search'}
                </button>
                <button onClick={() => setSearchType('violation')} className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
                  {t('tryViolationSearch') || 'Try Violation Search'}
                </button>
                <button onClick={() => setSearchType('licence')} className="px-4 py-2 text-sm font-medium text-text-secondary bg-surface border border-border rounded-xl hover:bg-surface-hover hover:border-primary/50 transition-colors">
                  {t('tryLicenceSearch') || 'Try Licence Search'}
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* How to Pay */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <h3 className="text-lg font-semibold text-text mb-6">{t('howToPay') || 'How to Pay Violations'}</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mx-auto mb-4 text-primary">
              <CreditCard className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('onlinePayment') || 'Online Payment'}</h4>
            <p className="text-text-secondary text-sm">{t('onlinePaymentDesc') || 'Pay instantly via credit card, digital wallet, or bank transfer'}</p>
          </div>
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-green-50 flex items-center justify-center mx-auto mb-4 text-green-600">
              <Building2 className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('serviceCentre') || 'Service Centre'}</h4>
            <p className="text-text-secondary text-sm">{t('serviceCentreDesc') || 'Pay at any ITMS service centre with cash or card'}</p>
          </div>
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-purple-50 flex items-center justify-center mx-auto mb-4 text-purple-600">
              <Wallet className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('mobileApp') || 'Mobile App'}</h4>
            <p className="text-text-secondary text-sm">{t('mobileAppDesc') || 'Pay through the ITMS mobile app with saved payment methods'}</p>
          </div>
        </div>
      </div>

      {/* Instalment Plans */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <h3 className="text-lg font-semibold text-text mb-6">{t('instalmentPlans') || 'Instalment Plans Available'}</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="p-4 bg-surface border border-border rounded-xl">
            <h4 className="font-semibold text-text mb-2">{t('plan3Months') || '3 Months'}</h4>
            <p className="text-text-secondary text-sm mb-3">{t('plan3MonthsDesc') || 'Split into 3 equal payments'}</p>
            <ul className="space-y-1 text-sm text-text-secondary">
              <li>• {t('noInterest') || '0% interest'}</li>
              <li>• {t('autoDeduct') || 'Auto-deduction available'}</li>
              <li>• {t('minAmount') || 'Minimum AED 500'}</li>
            </ul>
          </div>
          <div className="p-4 bg-surface border border-border rounded-xl">
            <h4 className="font-semibold text-text mb-2">{t('plan6Months') || '6 Months'}</h4>
            <p className="text-text-secondary text-sm mb-3">{t('plan6MonthsDesc') || 'Split into 6 equal payments'}</p>
            <ul className="space-y-1 text-sm text-text-secondary">
              <li>• {t('noInterest') || '0% interest'}</li>
              <li>• {t('autoDeduct') || 'Auto-deduction available'}</li>
              <li>• {t('minAmount') || 'Minimum AED 1000'}</li>
            </ul>
          </div>
          <div className="p-4 bg-surface border border-border rounded-xl">
            <h4 className="font-semibold text-text mb-2">{t('plan12Months') || '12 Months'}</h4>
            <p className="text-text-secondary text-sm mb-3">{t('plan12MonthsDesc') || 'Split into 12 equal payments'}</p>
            <ul className="space-y-1 text-sm text-text-secondary">
              <li>• {t('lowInterest') || 'Low interest (2%)'}</li>
              <li>• {t('autoDeduct') || 'Auto-deduction available'}</li>
              <li>• {t('minAmount') || 'Minimum AED 2000'}</li>
            </ul>
          </div>
        </div>
      </div>

      {/* Objection Process */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <h3 className="text-lg font-semibold text-text mb-6">{t('objectionProcess') || 'File an Objection'}</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mx-auto mb-4 text-primary">
              <FileText className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('step1Submit') || 'Submit Objection'}</h4>
            <p className="text-text-secondary text-sm">{t('step1SubmitDesc') || 'File objection online within 30 days of violation date with supporting evidence'}</p>
          </div>
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-yellow-50 flex items-center justify-center mx-auto mb-4 text-yellow-600">
              <Gavel className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('step2Review') || 'Committee Review'}</h4>
            <p className="text-text-secondary text-sm">{t('step2ReviewDesc') || 'Traffic committee reviews evidence and makes decision within 30 days'}</p>
          </div>
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-green-50 flex items-center justify-center mx-auto mb-4 text-green-600">
              <CheckCircle className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('step3Decision') || 'Decision & Payment'}</h4>
            <p className="text-text-secondary text-sm">{t('step3DecisionDesc') || 'If accepted, violation cancelled. If rejected, pay within 15 days'}</p>
          </div>
        </div>
      </div>

      {/* FAQ */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <h3 className="text-lg font-semibold text-text mb-6">{t('faq') || 'Frequently Asked Questions'}</h3>
        <div className="space-y-4">
          {[
            { q: 'Can I pay a violation in instalments?', a: 'Yes, instalment plans are available for fines over AED 500. Options: 3, 6, or 12 months with 0% interest for 3 and 6 months.' },
            { q: 'What happens if I don\'t pay a violation?', a: 'Unpaid violations accrue black points. At 24 points, your licence is suspended. Vehicle registration renewal is blocked until all fines are paid.' },
            { q: 'Can I object to a violation after 30 days?', a: 'Objections must be filed within 30 days of the violation date. Late objections are only accepted with valid justification (e.g., hospitalization).' },
            { q: 'How do I check my black points?', a: 'Log into your ITMS portal or use the mobile app. Black points are shown on your profile and violation history.' },
            { q: 'Can I pay someone else\'s violation?', a: 'Yes, you can pay any violation by entering the violation number or plate number. No account login required for payment.' },
          ].map((faq, i) => (
            <details key={i} className="group bg-bg rounded-xl border border-border p-4">
              <summary className="flex items-center justify-between cursor-pointer list-none">
                <span className="font-medium text-text">{faq.q}</span>
                <ChevronDown className={`w-5 h-5 text-text-muted transition-transform ${/* group open check */''}`} />
              </summary>
              <p className="mt-3 text-text-secondary">{faq.a}</p>
            </details>
          ))}
        </div>
      </div>
    </div>
  );
}