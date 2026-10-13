import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Search, Filter, DollarSign, Calculator, Plus, Minus, ArrowRight, CreditCard, Banknote, Wallet, Building2, Car, FileText, AlertTriangle, Calendar, Clock, MapPin, Shield, CheckCircle, XCircle, AlertCircle, Hourglass, TrendingUp, TrendingDown, Percent, Hash, AtSign, Euro, Pound, Yen, Bitcoin, Ethereum, Litecoin, Ripple, Dogecoin, Cardano, Polkadot, Chainlink, Uniswap, Aave, Compound, Maker, Synthetix, Yearn, Curve, Balancer, Sushi, Pancake, Venus, Jupiter, Mercury, Saturn, Uranus, Neptune, Pluto, Moon, Earth, Mars, Venus, Jupiter as JupiterIcon, Saturn as SaturnIcon, Uranus as UranusIcon, Neptune as NeptuneIcon, Galaxy, Star, Award, Trophy, Medal, Crown, Gem, Diamond, Sparkles, MagicWand, Wand, Target, Flag, Badge, Certificate, Key, Lock, Unlock, UnlockKeyhole, Shield, ShieldCheck, ShieldOff, ShieldAlert, ShieldPlus, ShieldMinus, ShieldX, ShieldCheck as ShieldCheckIcon, AlertCircle as AlertCircleIcon, AlertTriangle as AlertTriangleIcon, AlertOctagon, Bell, BellOff, BellPlus, BellMinus, BellRing, BellX, MessageSquare, MessageCircle, MessageSquare as MessageSquareIcon, MessageCircle as MessageCircleIcon, Mail, MailOpen, MailCheck, MailX, MailPlus, MailMinus, MailQuestion, MailSearch, Inbox, Send, Reply, ReplyAll, Forward, Archive, Trash2, Spam, Draft, Star as StarIcon, Important, Label, Filter as FilterIcon, Search as SearchIcon, ZoomIn, ZoomOut, Maximize2, Minimize2, RotateCcw, RotateCw, FlipHorizontal, FlipHorizontal2, FlipVertical, FlipVertical2, CornerUpLeft, CornerUpRight, CornerDownLeft, CornerDownRight, Move, MoveHorizontal, MoveVertical, MoveDiagonal, MoveDiagonal2, Resize, CornerUpLeft as CornerUpLeftIcon, CornerUpRight as CornerUpRightIcon, CornerDownLeft as CornerDownLeftIcon, CornerDownRight as CornerDownRightIcon } from 'lucide-react';
import { useState } from 'react';

export function PublicFeeCalculator() {
  const { t } = useTranslation();
  const [service, setService] = useState('vehicle_renewal');
  const [region, setRegion] = useState('dubai');
  const [customerCategory, setCustomerCategory] = useState('citizen');
  const [quantity, setQuantity] = useState(1);
  const [result, setResult] = useState(null);

  const services = [
    { id: 'vehicle_renewal', name: 'Vehicle Registration Renewal', baseFee: 350, unit: 'year' },
    { id: 'vehicle_new', name: 'New Vehicle Registration', baseFee: 1200, unit: 'once' },
    { id: 'licence_renewal', name: 'Driving Licence Renewal', baseFee: 300, unit: 'year' },
    { id: 'licence_new', name: 'New Driving Licence', baseFee: 600, unit: 'once' },
    { id: 'violation_payment', name: 'Violation Fine Payment', baseFee: 0, unit: 'per violation' },
    { id: 'plate_new', name: 'New Plate Registration', baseFee: 200, unit: 'once' },
    { id: 'plate_transfer', name: 'Plate Transfer', baseFee: 150, unit: 'once' },
    { id: 'appointment_booking', name: 'Appointment Booking', baseFee: 0, unit: 'per appointment' },
  ];

  const regions = [
    { id: 'dubai', name: 'Dubai', multiplier: 1.0 },
    { id: 'abu_dhabi', name: 'Abu Dhabi', multiplier: 1.0 },
    { id: 'sharjah', name: 'Sharjah', multiplier: 0.95 },
    { id: 'ajman', name: 'Ajman', multiplier: 0.95 },
    { id: 'fujairah', name: 'Fujairah', multiplier: 0.9 },
    { id: 'ras_al_khaimah', name: 'Ras Al Khaimah', multiplier: 0.9 },
    { id: 'umm_al_quwain', name: 'Umm Al Quwain', multiplier: 0.9 },
  ];

  const categories = [
    { id: 'citizen', name: 'Citizen', discount: 0 },
    { id: 'resident', name: 'Resident', discount: 0 },
    { id: 'senior', name: 'Senior Citizen (60+)', discount: 0.5 },
    { id: 'student', name: 'Student', discount: 0.25 },
    { id: 'pwd', name: 'Person of Determination', discount: 1.0 },
  ];

  const calculate = () => {
    const svc = services.find(s => s.id === service);
    const reg = regions.find(r => r.id === region);
    const cat = categories.find(c => c.id === customerCategory);
    
    if (!svc || !reg || !cat) return;
    
    let total = svc.baseFee * reg.multiplier * quantity;
    total = total * (1 - cat.discount);
    
    setResult({
      service: svc.name,
      region: reg.name,
      category: cat.name,
      baseFee: svc.baseFee,
      regionMultiplier: reg.multiplier,
      quantity,
      discount: cat.discount * 100,
      total: Math.round(total),
    });
  };

  const handleChange = () => {
    calculate();
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-text mb-2">{t('feeCalculator') || 'Fee Calculator'}</h1>
        <p className="text-text-secondary">{t('feeCalculatorDesc') || 'Calculate exact fees for any service based on your category and region'}</p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Calculator Form */}
        <div className="lg:col-span-2 space-y-6">
          <div className="bg-surface rounded-2xl border border-border p-6">
            <h2 className="text-lg font-semibold text-text mb-6">{t('calculateFee') || 'Calculate Your Fee'}</h2>
            
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-text-secondary mb-2">{t('service') || 'Service'} <span className="text-error">*</span></label>
                <select value={service} onChange={(e) => { setService(e.target.value); handleChange(); }} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
                  {services.map(s => <option key={s.id} value={s.id}>{s.name} (AED {s.baseFee}/{s.unit})</option>)}
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-text-secondary mb-2">{t('region') || 'Region'} <span className="text-error">*</span></label>
                <select value={region} onChange={(e) => { setRegion(e.target.value); handleChange(); }} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
                  {regions.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-text-secondary mb-2">{t('customerCategory') || 'Customer Category'} <span className="text-error">*</span></label>
                <select value={customerCategory} onChange={(e) => { setCustomerCategory(e.target.value); handleChange(); }} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
                  {categories.map(c => <option key={c.id} value={c.id}>{c.name} {c.discount > 0 && `(${c.discount * 100}% discount)`}</option>)}
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-text-secondary mb-2">{t('quantity') || 'Quantity/Years'}</label>
                <input type="number" min="1" max="10" value={quantity} onChange={(e) => { setQuantity(parseInt(e.target.value) || 1); handleChange(); }} className="w-full px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" />
              </div>

              <div className="pt-4 border-t border-border">
                <button onClick={calculate} className="w-full px-6 py-3 text-base font-semibold text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors flex items-center justify-center gap-2">
                  <Calculator className="w-5 h-5" />
                  {t('calculate') || 'Calculate'}
                </button>
              </div>
            </div>
          </div>

          {/* Fee Breakdown */}
          <div className="bg-surface rounded-2xl border border-border p-6">
            <h2 className="text-lg font-semibold text-text mb-6">{t('feeBreakdown') || 'Fee Breakdown'}</h2>
            
            <div className="space-y-3">
              <div className="flex justify-between text-text-secondary">
                <span>{t('baseFee') || 'Base Fee'}</span>
                <span>AED {result?.baseFee || 0}</span>
              </div>
              <div className="flex justify-between text-text-secondary">
                <span>{t('regionMultiplier') || 'Region Multiplier'}</span>
                <span>x{result?.regionMultiplier || 1}</span>
              </div>
              <div className="flex justify-between text-text-secondary">
                <span>{t('quantity') || 'Quantity'}</span>
                <span>x{quantity}</span>
              </div>
              {result?.discount > 0 && (
                <div className="flex justify-between text-green-600">
                  <span>{t('discount') || 'Discount'} ({result.discount}%)</span>
                  <span>-AED {Math.round((result.baseFee * result.regionMultiplier * quantity) * (result.discount / 100))}</span>
                </div>
              )}
              <div className="flex justify-between text-text-secondary border-t border-border pt-3">
                <span>{t('subtotal') || 'Subtotal'}</span>
                <span>AED {Math.round((result?.baseFee || 0) * (result?.regionMultiplier || 1) * quantity * (1 - (result?.discount || 0)))}</span>
              </div>
              <div className="flex justify-between text-text-secondary">
                <span>{t('vat') || 'VAT (5%)'}</span>
                <span>AED {Math.round((result?.baseFee || 0) * (result?.regionMultiplier || 1) * quantity * (1 - (result?.discount || 0)) * 0.05)}</span>
              </div>
              <div className="flex justify-between text-text border-t border-border pt-3">
                <span className="font-bold text-text">{t('total') || 'Total'}</span>
                <span className="font-bold text-primary text-lg">AED {result?.total ? Math.round(result.total * 1.05) : 0}</span>
              </div>
            </div>
          </div>
        </div>

        {/* Quick Reference */}
        <div className="lg:col-span-1 space-y-6">
          <div className="bg-surface rounded-2xl border border-border p-6">
            <h3 className="text-lg font-semibold text-text mb-4">{t('commonFees') || 'Common Fees'}</h3>
            <div className="space-y-3">
              {services.slice(0, 6).map(s => (
                <Link key={s.id} to={`/services/${s.id}`} className="flex items-center justify-between p-3 bg-bg rounded-xl border border-border hover:border-primary/50 hover:bg-primary/5 transition-all">
                  <div>
                    <p className="font-medium text-text">{s.name}</p>
                    <p className="text-sm text-text-secondary">AED {s.baseFee}/{s.unit}</p>
                  </div>
                  <ArrowRight className="w-4 h-4 text-text-muted" />
                </Link>
              ))}
            </div>
          </div>

          <div className="bg-surface rounded-2xl border border-border p-6">
            <h3 className="text-lg font-semibold text-text mb-4">{t('paymentMethods') || 'Accepted Payment Methods'}</h3>
            <div className="grid grid-cols-2 gap-3">
              {[
                { name: 'Credit/Debit Card', icon: CreditCard },
                { name: 'Bank Transfer', icon: Building2 },
                { name: 'Digital Wallet', icon: Wallet },
                { name: 'Cash at Centre', icon: DollarSign },
              ].map(p => (
                <div key={p.name} className="flex items-center gap-3 p-3 bg-bg rounded-xl border border-border">
                  <p.icon className="w-5 h-5 text-primary" />
                  <span className="text-sm text-text">{p.name}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="bg-primary/5 border border-primary/20 rounded-2xl p-6">
            <h3 className="text-lg font-semibold text-primary mb-2">{t('needHelp') || 'Need Help?'}</h3>
            <p className="text-primary/80 mb-4">{t('helpDesc') || 'Contact our support team for fee inquiries.'}</p>
            <Link to="/contact" className="inline-flex items-center gap-2 px-4 py-2 text-sm font-semibold text-primary bg-white rounded-xl hover:bg-primary/10 transition-colors">
              {t('contactSupport') || 'Contact Support'}
              <ArrowRight className="w-4 h-4" />
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}