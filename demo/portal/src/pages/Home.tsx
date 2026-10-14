import { LayoutDashboard, MousePointerClick, Radar, ShieldCheck } from 'lucide-react'
import { CountUp } from '../components'
import { useI18n } from '../i18n'

// Service names around the core; the angle spreads them over two rings. Each one opens the page where that service
// can be seen working (a module story opens on its own tab).
const inner = ['iam', 'traffic-file', 'violation', 'payment', 'vehicle', 'licensing', 'notification', 'audit']
const outer = ['gateway', 'keycloak', 'kafka', 'accident', 'permit', 'rental', 'marine', 'crossborder', 'search', 'reporting', 'workflow', 'rules']

const WHERE: Record<string, [string, string, string]> = {
  iam: ['qa', 'Access checks: who may do what (401 / 403)', 'فحوص الصلاحيات: من يحق له ماذا'],
  'traffic-file': ['profile', 'Your traffic file', 'ملفك المروري'],
  violation: ['control', 'Record a fine and watch the points', 'سجّل مخالفة وتابع النقاط'],
  payment: ['dashboard', 'Pay all fines → receipt', 'ادفع كل المخالفات ← الإيصال'],
  vehicle: ['modules?tab=vehicles', 'Register a car (insurance before the fee)', 'سجّل مركبة (التأمين قبل الرسوم)'],
  licensing: ['modules?tab=licensing', 'Licences and exams', 'الرخص والاختبارات'],
  notification: ['settings', 'Contact details and alerts', 'بيانات التواصل والتنبيهات'],
  audit: ['analytics', 'Live audit trail and hash-chain check', 'سجل التدقيق المباشر وفحص السلسلة'],
  gateway: ['platform', 'Live architecture and request rates', 'البنية الحية ومعدل الطلبات'],
  keycloak: ['qa', 'Token checks at the gateway', 'فحوص الرموز عند البوابة'],
  kafka: ['platform', 'Events between services', 'الأحداث بين الخدمات'],
  accident: ['modules?tab=accidents', 'Accident report with fault split', 'تقرير حادث مع توزيع المسؤولية'],
  permit: ['modules?tab=permits', 'Training permits', 'تصاريح التدريب'],
  rental: ['modules?tab=permits', 'Fine on a rented car goes to the renter (UAT-2)', 'مخالفة السيارة المؤجرة على المستأجر'],
  marine: ['modules?tab=road', 'Road permits and marine craft', 'تصاريح الطرق والمراكب البحرية'],
  crossborder: ['modules?tab=crossborder', 'Partner-state violation (UAT-5)', 'مخالفة من دولة شريكة'],
  search: ['modules', 'Federated search across services', 'البحث الموحّد عبر الخدمات'],
  reporting: ['reports', 'Run a report, download CSV', 'شغّل تقريرًا ونزّل CSV'],
  workflow: ['backoffice?tab=queue', 'Case work queue (claim, approve)', 'طابور الحالات (استلام، اعتماد)'],
  rules: ['services', 'Instalment plan: the rule decides', 'خطة التقسيط: القاعدة تقرر'],
}

function place(names: string[], radius: number, offset = 0) {
  return names.map((n, i) => {
    const a = (i / names.length) * Math.PI * 2 + offset
    return { n, left: `${50 + Math.cos(a) * radius}%`, top: `${50 + Math.sin(a) * radius}%` }
  })
}

export default function Home({ go, onCustomer }: { go: (r: string) => void; onCustomer: () => void }) {
  const { t, lang } = useI18n()
  const ar = lang === 'ar'
  const node = (p: { n: string; left: string; top: string }) => {
    const [route, en, a] = WHERE[p.n]
    const label = ar ? a : en
    return (
      <button key={p.n} className="node" style={{ left: p.left, top: p.top }} onClick={() => go(route)} title={label}
        aria-label={`${p.n}: ${label}`}><i />{p.n}</button>
    )
  }
  return (
    <div className="hero">
      <div>
        <span className="pill uae"><span className="dot live" /> {t.brandLong}</span>
        <h2>{t.heroA} <span className="grad-text">{t.heroB}</span></h2>
        <p>{t.tagline}</p>
        <div className="ctas">
          <button className="btn primary lg" onClick={onCustomer}><ShieldCheck size={18} /> {t.signInNational}</button>
          <button className="btn lg" onClick={() => go('control')}><Radar size={18} /> {t.staffCta}</button>
          <button className="btn ghost lg" onClick={() => go('dashboard')}><LayoutDashboard size={18} /> {t.heroCta}</button>
        </div>
        <div className="stats">
          <div className="stat"><b className="grad-text"><CountUp to={27} /></b><span>{t.stats.services}</span></div>
          <div className="stat"><b className="grad-text"><CountUp to={833} /></b><span>{t.stats.tests}</span></div>
          <div className="stat"><b className="grad-text"><CountUp to={438} /></b><span>{t.stats.reqs}</span></div>
          <div className="stat"><b className="grad-text"><CountUp to={27} /></b><span>{t.stats.dbs}</span></div>
        </div>
      </div>
      <div>
        <nav className="orbit" aria-label={ar ? 'خدمات النظام' : 'System services'}>
          <div className="ring r1" aria-hidden="true" />
          <div className="ring r2" aria-hidden="true" />
          <div className="core" aria-hidden="true">ITMS</div>
          {place(inner, 30, 0.3).map(node)}
          {place(outer, 46).map(node)}
        </nav>
        <p className="muted orbit-hint"><MousePointerClick size={14} /> {ar ? 'اضغط على أي خدمة لتراها تعمل' : 'Click any service to see it working'}</p>
      </div>
    </div>
  )
}
