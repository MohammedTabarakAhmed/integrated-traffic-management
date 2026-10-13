import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Search, Filter, Plus, Car, FileText, AlertTriangle, CreditCard, Calendar, FileCheck, Clock, Shield, Building2, MapPin, Phone, Mail, Globe, ArrowRight, CheckCircle, XCircle, AlertCircle, Hourglass, TrendingUp, Download, Upload, Eye, MoreVertical, User, Settings, Palette, Database, Server, Cloud, Lock, Unlock, BarChart2, PieChart, Activity, Table, FileSpreadsheet, FilePdf, Image, Video, Music, File, Folder, Home, Briefcase, Wallet, Gift, ShoppingBag, ShoppingCart, Package, Truck, Plane, Ship, Train, Bus, Car as CarIcon, Bike, Walk, Run, Swim, Heart, Brain, Zap, Flame, Droplet, Leaf, Tree, Mountain, Sun, Cloud, Rain, Snow, Wind, Thermometer, Gauge, Compass, Map, Anchor, Star, Award, Trophy, Medal, Crown, Gem, Diamond, Sparkles, MagicWand, Wand, Target, Flag, Badge, Certificate, Key, Lock, Unlock, UnlockKeyhole, Shield as ShieldIcon2, ShieldCheck, ShieldOff, ShieldAlert, ShieldPlus, ShieldMinus, ShieldX, ShieldCheck as ShieldCheckIcon, AlertCircle as AlertCircleIcon, AlertTriangle as AlertTriangleIcon, AlertOctagon, Bell, BellOff, BellPlus, BellMinus, BellRing, BellX, MessageSquare, MessageCircle, MessageSquare as MessageSquareIcon, MessageCircle as MessageCircleIcon, Mail, MailOpen, MailCheck, MailX, MailPlus, MailMinus, MailQuestion, MailSearch, Inbox, Send, Reply, ReplyAll, Forward, Archive, Trash2, Spam, Draft, Star as StarIcon, Important, Label, Filter as FilterIcon, Search as SearchIcon, ZoomIn, ZoomOut, Maximize2, Minimize2, RotateCcw, RotateCw, FlipHorizontal, FlipHorizontal2, FlipVertical, FlipVertical2, CornerUpLeft, CornerUpRight, CornerDownLeft, CornerDownRight, Move, MoveHorizontal, MoveVertical, MoveDiagonal, MoveDiagonal2, Resize, CornerUpLeft as CornerUpLeftIcon, CornerUpRight as CornerUpRightIcon, CornerDownLeft as CornerDownLeftIcon, CornerDownRight as CornerDownRightIcon, ChevronDown, ChevronUp } from 'lucide-react';
import { useState } from 'react';

interface ServiceItem {
  id: string;
  name: string;
  desc: string;
  requirements: string[];
  fee: string;
  duration: string;
  channels: string[];
}

interface ServiceCategory {
  category: string;
  icon: React.ComponentType<{ className?: string }>;
  color: string;
  services: ServiceItem[];
}

const services: ServiceCategory[] = [
  {
    category: 'Vehicles',
    icon: Car,
    color: 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400',
    services: [
      { id: 'veh_reg_new', name: 'New Vehicle Registration', desc: 'Register a brand new or imported vehicle', requirements: ['Customs certificate', 'Insurance', 'Inspection certificate', 'Owner Emirates ID'], fee: 'AED 1,200', duration: '30 min', channels: ['BACKOFFICE'] },
      { id: 'veh_reg_renew', name: 'Vehicle Registration Renewal', desc: 'Renew existing vehicle registration', requirements: ['Current registration card', 'Valid insurance', 'Inspection certificate (if >3 years)'], fee: 'AED 350', duration: '15 min', channels: ['PORTAL', 'BACKOFFICE', 'APP'] },
      { id: 'veh_transfer', name: 'Vehicle Ownership Transfer', desc: 'Transfer vehicle ownership to another person', requirements: ['Both parties Emirates ID', 'Registration card', 'Insurance', 'No fines on vehicle'], fee: 'AED 350', duration: '45 min', channels: ['BACKOFFICE'] },
      { id: 'veh_replace_plate', name: 'Replace Lost/Damaged Plate', desc: 'Get replacement for lost or damaged plate', requirements: ['Police report (if lost)', 'Owner Emirates ID', 'Registration card'], fee: 'AED 200', duration: '30 min', channels: ['BACKOFFICE', 'KIOSK'] },
      { id: 'veh_export', name: 'Vehicle Export Certificate', desc: 'Get certificate to export vehicle permanently', requirements: ['Registration card', 'Owner Emirates ID', 'Clear all fines'], fee: 'AED 500', duration: '30 min', channels: ['BACKOFFICE'] },
      { id: 'veh_temp_permit', name: 'Temporary Entry/Exit Permit', desc: 'Short-term permit for visiting vehicles', requirements: ['Vehicle documents', 'Insurance', 'Passport/Emirates ID'], fee: 'AED 100/day', duration: '15 min', channels: ['PORTAL', 'BACKOFFICE'] },
    ],
  },
  {
    category: 'Licences',
    icon: FileText,
    color: 'bg-green-50 text-green-600 dark:bg-green-900/20 dark:text-green-400',
    services: [
      { id: 'lic_new', name: 'New Driving Licence Issue', desc: 'Apply for first driving licence', requirements: ['Eye test', 'Theory test', 'Practical training', 'Practical test', 'Medical (if required)'], fee: 'AED 600+', duration: '60 min', channels: ['BACKOFFICE'] },
      { id: 'lic_renew', name: 'Driving Licence Renewal', desc: 'Renew expired or expiring licence', requirements: ['Current licence', 'Eye test', 'Emirates ID', 'Medical (if 65+)'], fee: 'AED 300', duration: '20 min', channels: ['PORTAL', 'BACKOFFICE', 'APP'] },
      { id: 'lic_replace', name: 'Replace Lost/Damaged Licence', desc: 'Replace lost, stolen, or damaged licence', requirements: ['Police report (if stolen)', 'Emirates ID', 'Eye test (if expired)'], fee: 'AED 200', duration: '15 min', channels: ['PORTAL', 'BACKOFFICE'] },
      { id: 'lic_upgrade', name: 'Upgrade Licence Class', desc: 'Add new vehicle class to existing licence', requirements: ['Current licence', 'Training completion', 'Practical test for new class'], fee: 'AED 400+', duration: '45 min', channels: ['BACKOFFICE'] },
      { id: 'lic_international', name: 'International Driving Permit', desc: 'Get IDP for driving abroad', requirements: ['Valid UAE licence', 'Passport copy', 'Photo'], fee: 'AED 150', duration: '15 min', channels: ['PORTAL', 'BACKOFFICE'] },
      { id: 'lic_foreign_exchange', name: 'Foreign Licence Exchange', desc: 'Exchange eligible foreign licence for UAE licence', requirements: ['Valid foreign licence', 'Translation', 'Eye test', 'Emirates ID'], fee: 'AED 500', duration: '30 min', channels: ['BACKOFFICE'] },
    ],
  },
  {
    category: 'Violations',
    icon: AlertTriangle,
    color: 'bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400',
    services: [
      { id: 'vio_pay', name: 'Pay Violation Fine', desc: 'Pay traffic violation fines online', requirements: ['Violation number or plate', 'Payment method'], fee: 'As per violation', duration: '5 min', channels: ['PORTAL', 'BACKOFFICE', 'APP', 'KIOSK'] },
      { id: 'vio_obj', name: 'File Objection', desc: 'Object to a violation within 30 days', requirements: ['Violation number', 'Supporting evidence', 'Objection form'], fee: 'AED 50', duration: '30 min', channels: ['PORTAL', 'BACKOFFICE'] },
      { id: 'vio_instalment', name: 'Instalment Plan', desc: 'Pay fine in instalments (3/6/12 months)', requirements: ['Fine > AED 500', 'Valid payment method'], fee: '0% interest (3/6 mo)', duration: '10 min', channels: ['PORTAL', 'BACKOFFICE'] },
      { id: 'vio_school', name: 'Traffic School', desc: 'Reduce black points by attending course', requirements: ['Eligible violation', 'Course booking'], fee: 'AED 200-500', duration: '4-8 hours', channels: ['BACKOFFICE'] },
    ],
  },
  {
    category: 'Plates',
    icon: Shield,
    color: 'bg-purple-50 text-purple-600 dark:bg-purple-900/20 dark:text-purple-400',
    services: [
      { id: 'plate_new', name: 'New Plate Registration', desc: 'Register new standard or special plate', requirements: ['Vehicle registration', 'Owner Emirates ID'], fee: 'AED 200+', duration: '30 min', channels: ['BACKOFFICE', 'KIOSK'] },
      { id: 'plate_transfer', name: 'Plate Transfer', desc: 'Transfer plate between vehicles/owners', requirements: ['Both vehicles registration', 'Both owners Emirates ID'], fee: 'AED 150', duration: '30 min', channels: ['BACKOFFICE'] },
      { id: 'plate_reserve', name: 'Reserve Plate Number', desc: 'Reserve a specific plate number', requirements: ['Application form', 'Deposit'], fee: 'AED 500+', duration: '15 min', channels: ['PORTAL', 'BACKOFFICE'] },
      { id: 'plate_auction', name: 'Premium Plate Auction', desc: 'Bid on premium/distinguished plates', requirements: ['Auction registration', 'Deposit'], fee: 'Auction price', duration: 'Auction period', channels: ['PORTAL', 'BACKOFFICE'] },
    ],
  },
  {
    category: 'Appointments',
    icon: Calendar,
    color: 'bg-orange-50 text-orange-600 dark:bg-orange-900/20 dark:text-orange-400',
    services: [
      { id: 'appt_book', name: 'Book Appointment', desc: 'Schedule service centre visit', requirements: ['Service type', 'Preferred date/time', 'Centre selection'], fee: 'Free', duration: '10 min', channels: ['PORTAL', 'BACKOFFICE', 'APP', 'CALLCENTRE'] },
      { id: 'appt_manage', name: 'Manage Appointments', desc: 'View, reschedule, or cancel appointments', requirements: ['Appointment reference'], fee: 'Free', duration: '5 min', channels: ['PORTAL', 'APP'] },
    ],
  },
  {
    category: 'Payments',
    icon: CreditCard,
    color: 'bg-pink-50 text-pink-600 dark:bg-pink-900/20 dark:text-pink-400',
    services: [
      { id: 'pay_make', name: 'Make Payment', desc: 'Pay for any service or violation', requirements: ['Reference number', 'Payment method'], fee: 'As per service', duration: '5 min', channels: ['PORTAL', 'BACKOFFICE', 'APP'] },
      { id: 'pay_history', name: 'Payment History', desc: 'View all past payments and receipts', requirements: ['Account login'], fee: 'Free', duration: 'Instant', channels: ['PORTAL', 'APP'] },
      { id: 'pay_refund', name: 'Request Refund', desc: 'Request refund for overpayment', requirements: ['Payment reference', 'Reason'], fee: 'Free', duration: '15 min', channels: ['BACKOFFICE'] },
    ],
  },
  {
    category: 'Documents',
    icon: FileCheck,
    color: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/20 dark:text-indigo-400',
    services: [
      { id: 'doc_upload', name: 'Upload Document', desc: 'Submit required documents for services', requirements: ['Document files (PDF/JPG/PNG)', 'Service reference'], fee: 'Free', duration: '5 min', channels: ['PORTAL', 'APP'] },
      { id: 'doc_verify', name: 'Verify Certificate', desc: 'Check authenticity of any certificate', requirements: ['Verification code or QR scan'], fee: 'Free', duration: 'Instant', channels: ['PORTAL', 'APP'] },
      { id: 'doc_download', name: 'Download Certificates', desc: 'Download digital copies of certificates', requirements: ['Account login'], fee: 'Free', duration: 'Instant', channels: ['PORTAL', 'APP'] },
    ],
  },
];

interface ServiceCardProps {
  service: ServiceItem;
  t: (key: string, defaultValue?: string) => string;
}

function ServiceCard({ service, t }: ServiceCardProps) {
  return (
    <Link to={`/services/${service.id}`} className="group p-5 bg-bg rounded-xl border border-border hover:border-primary/50 hover:shadow-md hover:bg-primary/5 transition-all duration-300">
      <h3 className="font-semibold text-text group-hover:text-primary transition-colors">{service.name}</h3>
      <p className="text-text-secondary text-sm mb-4 line-clamp-2">{service.desc}</p>
      <div className="space-y-2 text-sm">
        <div className="flex items-center gap-2 text-text-secondary">
          <Clock className="w-4 h-4" />
          <span>{service.duration}</span>
        </div>
        <div className="flex items-center gap-2 text-text-secondary">
          <CheckCircle className="w-4 h-4" />
          <span>{service.requirements.length} requirements</span>
        </div>
      </div>
      <div className="flex flex-wrap gap-1 mt-3">
        {service.channels.map((ch, i) => (
          <span key={i} className="px-2 py-0.5 text-xs bg-primary/10 text-primary rounded-full">{ch}</span>
        ))}
      </div>
    </Link>
  );
}

interface CategorySectionProps {
  cat: ServiceCategory;
  expanded: boolean;
  onToggle: () => void;
  t: (key: string, defaultValue?: string) => string;
}

function CategorySection({ cat, expanded, onToggle, t }: CategorySectionProps) {
  return (
    <section className="bg-surface rounded-2xl border border-border overflow-hidden">
      <div className="p-6 border-b border-border flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div className="flex items-center gap-4">
          <div className={`p-3 rounded-xl ${cat.color}`}>
            <cat.icon className="w-6 h-6" aria-hidden="true" />
          </div>
          <div>
            <h2 className="text-xl font-semibold text-text">{cat.category}</h2>
            <p className="text-text-secondary">{cat.services.length} services</p>
          </div>
        </div>
        <button
          onClick={onToggle}
          className={`px-4 py-2 rounded-xl transition-colors flex items-center gap-2 ${expanded ? 'bg-primary text-white' : 'bg-surface border border-border text-text-secondary hover:bg-primary/5'}`}
        >
          {expanded ? (
            <>
              <ChevronUp className="w-4 h-4" />
              {t('collapse') || 'Collapse'}
            </>
          ) : (
            <>
              <ChevronDown className="w-4 h-4" />
              {t('expand') || 'Expand'}
            </>
          )}
        </button>
      </div>

      {expanded && (
        <div className="p-6">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {cat.services.map((service) => (
              <ServiceCard key={service.id} service={service} t={t} />
            ))}
          </div>
        </div>
      )}
    </section>
  );
}

export function PublicServiceGuide() {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const toggleExpand = (id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const filteredCategories = services.filter(cat => categoryFilter === 'all' || cat.category === categoryFilter);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-text mb-2">{t('serviceGuide') || 'Service Guide'}</h1>
        <p className="text-text-secondary">{t('serviceGuideDesc') || 'Browse all available ITMS services with requirements, fees, and processing times'}</p>
      </div>

      <div className="flex flex-col sm:flex-row gap-4 mb-8">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('searchServices') || 'Search services by name, keyword...'}
            className="w-full pl-10 pr-4 py-3 bg-surface border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
          />
        </div>
        <select value={categoryFilter} onChange={(e) => setCategoryFilter(e.target.value)} className="px-4 py-3 bg-surface border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByCategory')}>
          <option value="all">{t('allCategories')}</option>
          {services.map(cat => <option key={cat.category} value={cat.category}>{cat.category}</option>)}
        </select>
      </div>

      {filteredCategories.map((cat) => (
        <CategorySection
          key={cat.category}
          cat={cat}
          expanded={expandedIds.has(cat.category)}
          onToggle={() => toggleExpand(cat.category)}
          t={t}
        />
      ))}

      <div className="text-center p-8 bg-primary/5 rounded-2xl border border-primary/20">
        <h3 className="text-xl font-semibold text-primary mb-2">{t('cantFindService') || 'Can\'t find what you\'re looking for?'}</h3>
        <p className="text-primary/80 mb-4">{t('cantFindServiceDesc') || 'Contact our support team for assistance with any service.'}</p>
        <Link to="/contact" className="inline-flex items-center gap-2 px-6 py-3 text-base font-semibold text-primary bg-white rounded-xl hover:bg-primary/10 transition-colors">
          {t('contactSupport') || 'Contact Support'}
          <ArrowRight className="w-4 h-4" />
        </Link>
      </div>
    </div>
  );
}