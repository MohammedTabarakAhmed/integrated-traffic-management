import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Search, Shield, CheckCircle, XCircle, AlertCircle, Eye, Download, Copy, Share2, Loader2, FileText, QrCode, Verified, Award, Badge, User, Calendar, Clock, Shield as ShieldIcon, Lock, Unlock, AlertTriangle, Info, Check, X, Search as SearchIcon, Filter, FilterX, Home, Building2, Car, FileText as FileTextIcon, AlertTriangle as AlertTriangleIcon, CreditCard, Calendar as CalendarIcon, Clock as ClockIcon, MapPin, Phone, Mail, Globe, Shield as ShieldIcon2 } from 'lucide-react';
import { Link } from 'react-router-dom';

export function PublicCertificateVerify() {
  const { t } = useTranslation();
  const [verificationCode, setVerificationCode] = useState('');
  const [result, setResult] = useState<{
    valid: boolean;
    certificate?: {
      type: string;
      number: string;
      holder: string;
      issueDate: string;
      expiryDate: string;
      issuingAuthority: string;
      qrCode: string;
    };
    error?: string;
  } | null>(null);
  const [loading, setLoading] = useState(false);

  const verify = async () => {
    if (!verificationCode.trim()) return;
    
    setLoading(true);
    setResult(null);
    
    // Simulate API call
    await new Promise(resolve => setTimeout(resolve, 1500));
    
    // Mock verification result
    if (verificationCode.length >= 10) {
      setResult({
        valid: true,
        certificate: {
          type: 'Driving Licence',
          number: 'DL-2024-001234',
          holder: 'Ahmed Al Mansouri',
          issueDate: '2022-01-15',
          expiryDate: '2032-01-14',
          issuingAuthority: 'RTA Dubai',
          qrCode: verificationCode,
        },
      });
    } else {
      setResult({
        valid: false,
        error: 'Invalid verification code format',
      });
    }
    
    setLoading(false);
  };

  const copyCode = () => {
    navigator.clipboard.writeText(verificationCode);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-text mb-2">{t('certificateVerification') || 'Certificate Verification'}</h1>
        <p className="text-text-secondary">{t('certificateVerificationDesc') || 'Verify the authenticity of any ITMS certificate or licence using the verification code'}</p>
      </div>

      {/* Verification Form */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <h2 className="text-lg font-semibold text-text mb-6">{t('enterVerificationCode') || 'Enter Verification Code'}</h2>
        
        <div className="flex flex-col sm:flex-row gap-4">
          <div className="flex-1 relative">
            <input
              type="text"
              value={verificationCode}
              onChange={(e) => setVerificationCode(e.target.value.toUpperCase())}
              placeholder={t('verificationCodePlaceholder') || 'Enter verification code (e.g., DL-2024-001234)'}
              className="w-full px-4 py-3 pl-11 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary text-uppercase"
              maxLength={20}
            />
            <SearchIcon className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
          </div>
          <button
            onClick={verify}
            disabled={loading || !verificationCode.trim()}
            className="px-6 py-3 text-base font-semibold text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
          >
            {loading ? (
              <>
                <Loader2 className="w-5 h-5 animate-spin" />
                {t('verifying') || 'Verifying...'}
              </>
            ) : (
              <>
                <SearchIcon className="w-5 h-5" />
                {t('verify') || 'Verify'}
              </>
            )}
          </button>
        </div>

        <p className="mt-4 text-sm text-text-muted">{t('verificationCodeHelp') || 'Enter the verification code found on the certificate or scan the QR code using the mobile app'}</p>
      </div>

      {/* Result */}
      {result && (
        <div className={`bg-surface rounded-2xl border border-border p-6 ${result.valid ? 'border-green-500/50 bg-green-50/50' : 'border-red-500/50 bg-red-50/50'}`}>
          <div className="flex items-start gap-4">
            <div className={`p-3 rounded-xl ${result.valid ? 'bg-green-500' : 'bg-red-500'}`}>
              {result.valid ? (
                <CheckCircle className="w-6 h-6 text-white" />
              ) : (
                <XCircle className="w-6 h-6 text-white" />
              )}
            </div>
            <div className="flex-1">
              <h3 className={`text-lg font-semibold ${result.valid ? 'text-green-700' : 'text-red-700'}`}>
                {result.valid ? t('certificateValid') || 'Certificate is Valid' : t('certificateInvalid') || 'Certificate is Invalid'}
              </h3>
              {result.error && (
                <p className="text-red-600 mt-1">{result.error}</p>
              )}
            </div>
          </div>

          {result.valid && result.certificate && (
            <div className="mt-6 pt-6 border-t border-border">
              <h4 className="font-semibold text-text mb-4">{t('certificateDetails') || 'Certificate Details'}</h4>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <p className="text-sm text-text-secondary">{t('certificateType') || 'Type'}</p>
                  <p className="font-medium text-text">{result.certificate.type}</p>
                </div>
                <div>
                  <p className="text-sm text-text-secondary">{t('certificateNumber') || 'Number'}</p>
                  <p className="font-medium text-text font-mono">{result.certificate.number}</p>
                </div>
                <div>
                  <p className="text-sm text-text-secondary">{t('holder') || 'Holder'}</p>
                  <p className="font-medium text-text">{result.certificate.holder}</p>
                </div>
                <div>
                  <p className="text-sm text-text-secondary">{t('issuingAuthority') || 'Issuing Authority'}</p>
                  <p className="font-medium text-text">{result.certificate.issuingAuthority}</p>
                </div>
                <div>
                  <p className="text-sm text-text-secondary">{t('issueDate') || 'Issue Date'}</p>
                  <p className="font-medium text-text">{new Date(result.certificate.issueDate).toLocaleDateString()}</p>
                </div>
                <div>
                  <p className="text-sm text-text-secondary">{t('expiryDate') || 'Expiry Date'}</p>
                  <p className="font-medium text-text">{new Date(result.certificate.expiryDate).toLocaleDateString()}</p>
                </div>
              </div>

              <div className="mt-6 pt-6 border-t border-border">
                <h4 className="font-semibold text-text mb-3">{t('qrCode') || 'QR Code'}</h4>
                <div className="flex items-center justify-center gap-6">
                  <div className="p-4 bg-white rounded-xl border border-border">
                    <QrCode className="w-32 h-32 text-black" value={result.certificate.qrCode} />
                  </div>
                  <div className="flex flex-col gap-3">
                    <p className="text-sm text-text-secondary">{t('scanToVerify') || 'Scan with ITMS mobile app to verify'}</p>
                    <div className="flex gap-3">
                      <button className="px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors flex items-center gap-2">
                        <Download className="w-4 h-4" />
                        {t('downloadQR') || 'Download QR'}
                      </button>
                      <button onClick={copyCode} className="px-4 py-2 text-sm font-medium text-primary bg-primary/10 rounded-xl hover:bg-primary/20 transition-colors flex items-center gap-2">
                        <Copy className="w-4 h-4" />
                        {t('copyCode') || 'Copy Code'}
                      </button>
                    </div>
                  </div>
                </div>
              </div>

              <div className="mt-6 pt-6 border-t border-border">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <Verified className="w-6 h-6 text-green-600" />
                    <span className="font-medium text-green-700">{t('officiallyVerified') || 'Officially Verified by ITMS'}</span>
                  </div>
                  <span className="text-sm text-text-secondary">{t('verifiedOn') || 'Verified on'} {new Date().toLocaleDateString()}</span>
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* How it works */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <h3 className="text-lg font-semibold text-text mb-6">{t('howItWorks') || 'How It Works'}</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mx-auto mb-4 text-primary">
              <SearchIcon className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('step1') || 'Enter Code'}</h4>
            <p className="text-text-secondary text-sm">{t('step1Desc') || 'Enter the verification code from the certificate or scan the QR code'}</p>
          </div>
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mx-auto mb-4 text-primary">
              <ShieldIcon className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('step2') || 'Verify'}</h4>
            <p className="text-text-secondary text-sm">{t('step2Desc') || 'Our system checks the code against the official ITMS database'}</p>
          </div>
          <div className="text-center p-4">
            <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center mx-auto mb-4 text-primary">
              <CheckCircle className="w-6 h-6" />
            </div>
            <h4 className="font-semibold text-text mb-2">{t('step3') || 'Get Result'}</h4>
            <p className="text-text-secondary text-sm">{t('step3Desc') || 'Instantly see if the certificate is valid with full details'}</p>
          </div>
        </div>
      </div>

      {/* Supported Certificates */}
      <div className="bg-surface rounded-2xl border border-border p-6">
        <h3 className="text-lg font-semibold text-text mb-6">{t('supportedCertificates') || 'Supported Certificates'}</h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {[
            { name: 'Driving Licence', icon: User, types: ['New', 'Renewal', 'Replacement', 'International'] },
            { name: 'Vehicle Registration', icon: Car, types: ['New', 'Renewal', 'Transfer', 'Export'] },
            { name: 'Violation Receipt', icon: AlertTriangle, types: ['Payment', 'Objection', 'Instalment'] },
            { name: 'Inspection Certificate', icon: ShieldIcon, types: ['Vehicle', 'Plate', 'Technical'] },
          ].map((cert, i) => (
            <div key={i} className="p-4 bg-bg rounded-xl border border-border">
              <div className="flex items-center gap-3 mb-3">
                <div className="p-2 rounded-lg bg-primary/10 text-primary">
                  <cert.icon className="w-5 h-5" />
                </div>
                <h4 className="font-semibold text-text">{cert.name}</h4>
              </div>
              <ul className="space-y-1 text-sm text-text-secondary">
                {cert.types.map(t => (
                  <li key={t} className="flex items-center gap-2">
                    <CheckCircle className="w-4 h-4 text-green-600" />
                    {t}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}