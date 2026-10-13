import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'

export type Lang = 'ar' | 'en'

const en = {
  brand: 'ITMS',
  brandLong: 'Integrated Traffic Management System',
  heroA: 'Every road, licence and fine.',
  heroB: 'One live platform.',
  tagline: 'One national platform for traffic files, licences, vehicles, violations, payments and more.',
  signInNational: 'Sign in with National Digital ID',
  signOut: 'Sign out',
  language: 'العربية',
  nav: {
    home: 'Overview',
    dashboard: 'My dashboard',
    control: 'Control room',
    modules: 'Modules',
    analytics: 'Analytics',
    assistant: 'Assistant',
    platform: 'Platform',
  },
  sections: { citizen: 'Citizen portal', staff: 'Staff console', system: 'System' },
  hello: 'Welcome back',
  file: 'Traffic file',
  fileStatus: 'File status',
  region: 'Region',
  points: 'Black points',
  pointsOf: 'of',
  pointsHint: 'A suspension starts at the threshold.',
  suspended: 'Licence suspended',
  until: 'until',
  violations: 'Violations',
  noViolations: 'No violations. Drive safe!',
  openFines: 'Open fines',
  totalDue: 'Total due',
  transactions: 'Transactions',
  noTransactions: 'No transactions yet.',
  inbox: 'Inbox',
  noMessages: 'No messages.',
  status: {
    OPEN: 'Open',
    PAID: 'Paid',
    CANCELLED: 'Cancelled',
    ACTIVE: 'Active',
    DRAFT: 'Draft',
    SUBMITTED: 'Submitted',
    IN_PROGRESS: 'In progress',
    COMPLETED: 'Completed',
    REJECTED: 'Rejected',
  } as Record<string, string>,
  loading: 'Loading…',
  retry: 'Retry',
  noFile: 'No traffic file is linked to this national ID yet.',
  stats: { services: 'microservices', tests: 'automated tests', reqs: 'BRD requirements', dbs: 'databases' },
  heroCta: 'Open my dashboard',
  staffCta: 'Open the control room',
  comingSoon: 'Coming up in the next demo step.',
}

type Dict = typeof en

const ar: Dict = {
  brand: 'ITMS',
  brandLong: 'نظام إدارة المرور المتكامل',
  heroA: 'كل طريق ورخصة ومخالفة.',
  heroB: 'منصة واحدة حيّة.',
  tagline: 'منصة وطنية واحدة للملفات المرورية والرخص والمركبات والمخالفات والمدفوعات وغيرها.',
  signInNational: 'الدخول بالهوية الرقمية الوطنية',
  signOut: 'تسجيل الخروج',
  language: 'English',
  nav: {
    home: 'نظرة عامة',
    dashboard: 'لوحتي',
    control: 'غرفة التحكم',
    modules: 'الوحدات',
    analytics: 'التحليلات',
    assistant: 'المساعد',
    platform: 'المنصة',
  },
  sections: { citizen: 'بوابة المتعامل', staff: 'وحدة الموظفين', system: 'النظام' },
  hello: 'مرحبًا بعودتك',
  file: 'الملف المروري',
  fileStatus: 'حالة الملف',
  region: 'المنطقة',
  points: 'النقاط السوداء',
  pointsOf: 'من',
  pointsHint: 'يبدأ الإيقاف عند بلوغ الحد.',
  suspended: 'الرخصة موقوفة',
  until: 'حتى',
  violations: 'المخالفات',
  noViolations: 'لا توجد مخالفات. قيادة آمنة!',
  openFines: 'غرامات مستحقة',
  totalDue: 'الإجمالي المستحق',
  transactions: 'المعاملات',
  noTransactions: 'لا توجد معاملات بعد.',
  inbox: 'صندوق الرسائل',
  noMessages: 'لا توجد رسائل.',
  status: {
    OPEN: 'مستحقة',
    PAID: 'مدفوعة',
    CANCELLED: 'ملغاة',
    ACTIVE: 'نشط',
    DRAFT: 'مسودة',
    SUBMITTED: 'مقدمة',
    IN_PROGRESS: 'قيد الإجراء',
    COMPLETED: 'مكتملة',
    REJECTED: 'مرفوضة',
  },
  loading: 'جارٍ التحميل…',
  retry: 'إعادة المحاولة',
  noFile: 'لا يوجد ملف مروري مرتبط بهذه الهوية بعد.',
  stats: { services: 'خدمة مصغرة', tests: 'اختبار آلي', reqs: 'متطلب عمل', dbs: 'قاعدة بيانات' },
  heroCta: 'افتح لوحتي',
  staffCta: 'افتح غرفة التحكم',
  comingSoon: 'ضمن خطوة العرض التالية.',
}

const dicts = { en, ar }
const Ctx = createContext<{ lang: Lang; t: Dict; toggle: () => void }>({ lang: 'ar', t: ar, toggle: () => {} })

const initial = (): Lang => {
  try {
    return (localStorage.getItem('itms.lang') as Lang) || 'ar'
  } catch {
    return 'ar'
  }
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLang] = useState<Lang>(initial)
  useEffect(() => {
    document.documentElement.lang = lang
    document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr'
    try {
      localStorage.setItem('itms.lang', lang)
    } catch {
      /* ignore */
    }
  }, [lang])
  return (
    <Ctx.Provider value={{ lang, t: dicts[lang], toggle: () => setLang((l) => (l === 'ar' ? 'en' : 'ar')) }}>
      {children}
    </Ctx.Provider>
  )
}

export const useI18n = () => useContext(Ctx)
