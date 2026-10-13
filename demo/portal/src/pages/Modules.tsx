import { Fragment, useCallback, useEffect, useState, type ReactNode } from 'react'
import { Car, CheckCircle2, ChevronRight, CircleDashed, Code2, Globe2, GraduationCap, KeyRound, Loader2, Route as RouteIcon, Search, ShieldCheck, Siren, UserCheck, Users, XCircle } from 'lucide-react'
import { ApiError, call, money, when } from '../api'
import { Drawer, Skeleton, Timeline } from '../components'
import { load, signInStaff, type Kind } from '../auth'
import { useI18n } from '../i18n'

// Module tour (demo D2.4): one short, live story per increment over the existing APIs. Maker steps run as dev.maker,
// checker steps as dev.checker, so the second pair of eyes (maker-checker) is visible on screen.

type StepState = 'run' | 'ok' | 'stop' | 'fail'
type Step = { en: string; ar: string; who: 'maker' | 'checker' | 'partner' | 'system'; state: StepState; note?: string; json?: unknown }
type Ctx = {
  step: <T>(en: string, ar: string, who: Step['who'], fn: () => Promise<T>, opts?: { expectFail?: string; note?: (r: T) => string }) => Promise<T>
  m: <T>(method: 'GET' | 'POST', path: string, body?: unknown, channel?: string) => Promise<T>
  c: <T>(method: 'GET' | 'POST', path: string, body?: unknown, channel?: string) => Promise<T>
  show: (node: ReactNode) => void
  lang: string
}
type Action = { en: string; ar: string; tone?: 'primary' | 'bad'; run: (x: Ctx) => Promise<void> }
type Tour = { id: string; inc: string; icon: ReactNode; en: string; ar: string; uat?: string; ids: string; textEn: string; textAr: string; actions: Action[] }

class Stopped extends Error {}
type Hit = { type: string; id: string; reference: string; region?: string; fields: Record<string, unknown> }
type AuditRow = { seq: number; occurredAt: string; service: string; action: string; actorId: string; actorUsername?: string | null; channel: string; hash: string }
// Search result type -> audit entity type (the record's history lives in the central audit chain).
const AUDIT_TYPE: Record<string, string> = { VIOLATION: 'Violation', VEHICLE: 'Vehicle', TRAFFIC_FILE: 'TrafficFile', ACCIDENT: 'Accident', LICENCE: 'Licence' }
const rnd = () => String(Date.now()).slice(-7)
const iso = (ms: number) => new Date(Date.now() + ms).toISOString().replace(/\.\d+Z$/, 'Z')
const HOUR = 3_600_000
const DAY = 24 * HOUR
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))
async function until<T>(fn: () => Promise<T>, ok: (v: T) => boolean, seconds = 40): Promise<T> {
  for (let i = 0; i < seconds; i++) {
    const v = await fn()
    if (ok(v)) return v
    await wait(1000)
  }
  throw new Error('timed out')
}

function Plate({ number }: { number: string }) {
  const [prefix, num] = number.split(' ')
  return (
    <div className="plate" aria-label={`Plate ${number}`}>
      <span className="plate-emirate">DUBAI<br /><b>دبي</b></span>
      <span className="plate-code">{prefix}</span>
      <span className="plate-num">{num}</span>
    </div>
  )
}

const LABELS: Record<string, string> = {
  VIN: 'رقم الهيكل', Status: 'الحالة', 'Valid until': 'صالح حتى', File: 'الملف', Class: 'الفئة', Report: 'التقرير', 'Party 2': 'الطرف 2',
  Permit: 'التصريح', 'Valid to': 'صالح حتى', Contract: 'العقد', Violation: 'المخالفة', Owner: 'المالك', Responsible: 'المسؤول',
  Route: 'المسار', Hull: 'رقم البدن', 'Home port': 'ميناء التسجيل',
}

function Kv({ rows }: { rows: [string, ReactNode][] }) {
  const { lang } = useI18n()
  return <dl className="kv" style={{ margin: '12px 0 0' }}>{rows.map(([k, v]) => <Fragment key={k}><dt>{lang === 'ar' ? LABELS[k] ?? k : k}</dt><dd>{v}</dd></Fragment>)}</dl>
}

// ---------- the stories ----------

const vehicleRun = (kind: 'ok' | 'expired' | 'down'): Action['run'] => async ({ step, m, show, lang }) => {
  const n = rnd()
  const vin = kind === 'expired' ? `WDB${n.padStart(9, '0')}00000` : kind === 'down' ? `WDB${n.padStart(9, '0')}99999` : `WDB${n.padStart(13, '0')}1`
  const owner = `TF-DEMO-${n}`
  const spec = { engineNo: `ENG-${n}`, make: 'TOYOTA', model: 'LAND_CRUISER', modelYear: new Date().getFullYear(), bodyType: 'SUV', colours: ['WHITE'],
    fuelType: 'PETROL', steering: 'LEFT', seats: 7, emptyWeightKg: 2500, grossWeightKg: 3300, axles: 2, cylinders: 8, category: 'PRIVATE' }
  await step('Customs certificate received (VL-010)', 'وصول شهادة الجمارك', 'partner', () => m('POST', '/v1/customs-certificates',
    { certificateNo: `CC-${vin}`, source: 'CUSTOMS', issuerCode: 'DXB_CUSTOMS', vin, spec, issuedOn: new Date().toISOString().slice(0, 10) }, 'PARTNER'),
  { note: () => `CC-${vin}` })
  await step('Vehicle created from the certificate (VL-002)', 'إنشاء المركبة من الشهادة', 'maker', () =>
    m('POST', '/v1/vehicles', { customsCertificateNo: `CC-${vin}`, ownerFileNumber: owner, region: 'DXB' }), { note: () => `${vin} · Toyota Land Cruiser` })
  const reg = await step<{ payableId: string; amountMinor: number; currency: string }>('Insurance checked, then the fee (UAT-3)', 'التحقق من التأمين ثم الرسوم', 'system',
    () => m('POST', `/v1/vehicles/${vin}/registrations`, { customerCategory: 'CITIZEN' }, 'PORTAL'),
    { expectFail: kind === 'ok' ? undefined : kind === 'expired' ? 'INSURANCE_EXPIRED' : 'INSURANCE_UNAVAILABLE', note: (r) => `${money(r.amountMinor, r.currency, lang)} payable` })
  const basket = await step<{ id: string }>('Basket opened at the counter', 'فتح سلة الدفع في المركز', 'maker', () => m('POST', '/v1/baskets', { payableIds: [reg.payableId] }))
  await step('Paid in cash, receipt issued (FN-001)', 'الدفع نقدًا وإصدار الإيصال', 'maker', () =>
    m('POST', `/v1/baskets/${basket.id}/payments`, { method: 'CASH', counterReference: `TILL-DEMO-${n}` }))
  const v = await step<{ plateNumber: string; registrationExpiry: string; status: string }>('Payment event → registered, plate assigned', 'حدث الدفع ← تسجيل وتخصيص لوحة', 'system',
    () => until(() => m<{ status: string; plateNumber: string; registrationExpiry: string }>('GET', `/v1/vehicles/${vin}`), (x) => x.status === 'REGISTERED'), { note: (x) => `${x.status} · ${x.plateNumber}` })
  try { sessionStorage.setItem('itms.demo.plate', v.plateNumber) } catch { /* ignore */ }
  show(<div style={{ display: 'flex', gap: 20, alignItems: 'center', flexWrap: 'wrap' }}><Plate number={v.plateNumber} />
    <Kv rows={[['VIN', <span className="mono">{vin}</span>], ['Status', v.status], ['Valid until', v.registrationExpiry]]} /></div>)
}

const tours: Tour[] = [
  { id: 'vehicles', inc: '2', icon: <Car size={18} />, en: 'Vehicles & plates', ar: 'المركبات واللوحات', uat: 'UAT-3', ids: 'VL-002 · VL-010 · VL-024 · FN-001',
    textEn: 'A new car arrives from customs. Insurance is verified before any fee is charged; after payment the plate is assigned automatically.',
    textAr: 'تصل مركبة جديدة من الجمارك. يُتحقق من التأمين قبل أي رسوم، وبعد الدفع تُخصَّص اللوحة تلقائيًا.',
    actions: [
      { en: 'Register a new car', ar: 'تسجيل مركبة جديدة', tone: 'primary', run: vehicleRun('ok') },
      { en: 'Try with expired insurance', ar: 'تجربة بتأمين منتهٍ', tone: 'bad', run: vehicleRun('expired') },
      { en: 'Try with the insurer offline', ar: 'تجربة وشركة التأمين غير متاحة', tone: 'bad', run: vehicleRun('down') },
    ] },
  { id: 'licensing', inc: '3', icon: <GraduationCap size={18} />, en: 'Licensing & exams', ar: 'الرخص والاختبارات', ids: 'DL-030 · DL-031 · DL-036',
    textEn: 'A learner opens a learning file for a light-vehicle licence and moves through training to test readiness.',
    textAr: 'يفتح المتدرب ملف تعلّم لرخصة مركبة خفيفة وينتقل من التدريب إلى الجاهزية للاختبار.',
    actions: [{ en: 'Open a learning file', ar: 'فتح ملف تعلّم', tone: 'primary', run: async ({ step, m, show }) => {
      const holder = `TF-DEMO-L${rnd()}`
      const lf = await step<{ id: string; number: string; status: string }>('Learning file opened (LIGHT, automatic)', 'فتح ملف التعلّم (خفيفة، أوتوماتيك)', 'maker',
        () => m('POST', '/v1/learning-files', { trafficFileNumber: holder, classCode: 'LIGHT', gearbox: 'AUTOMATIC', region: 'DXB' }), { note: (r) => `${r.number} · ${r.status}` })
      await step<{ status: string }>('Training started', 'بدء التدريب', 'maker', () => m('POST', `/v1/learning-files/${lf.id}/transitions`, { action: 'start-training' }), { note: (r) => r.status })
      const done = await step<{ status: string; number: string; trainingHours: number }>('Ready for the test', 'جاهز للاختبار', 'maker',
        () => m('POST', `/v1/learning-files/${lf.id}/transitions`, { action: 'ready-for-test' }), { note: (r) => r.status })
      show(<Kv rows={[['File', <span className="mono">{done.number}</span>], ['Class', 'LIGHT · AUTOMATIC'], ['Status', done.status]]} />)
    } }] },
  { id: 'accidents', inc: '6', icon: <Siren size={18} />, en: 'Accidents', ar: 'الحوادث', ids: 'AC-002 · AC-005 · AC-006',
    textEn: 'An officer files an accident from the field device. Both drivers agree 70/30, so fault is decided at once with no investigation.',
    textAr: 'يسجّل الضابط حادثًا من الجهاز الميداني. اتفق السائقان على 70/30 فتُحدد المسؤولية فورًا.',
    actions: [{ en: 'Report an accident', ar: 'تسجيل حادث', tone: 'primary', run: async ({ step, m, show }) => {
      const n = rnd()
      const a = await step<{ id: string }>('Accident reported from the field', 'تسجيل الحادث ميدانيًا', 'maker', () => m('POST', '/v1/accidents', {
        region: 'DXB', occurredAt: iso(-HOUR), location: 'Sheikh Zayed Road, km 27', origin: 'OFFICER_DEVICE', evidenceDocumentIds: [`DOC-${n}`],
        parties: [
          { role: 'DRIVER', trafficFileNumber: `TF-DEMO-A1${n}`, vehiclePlate: 'S 401', faultPercent: 70, agrees: true, seatPosition: 'FRONT_LEFT', intoxicationStatus: 'NEGATIVE' },
          { role: 'DRIVER', trafficFileNumber: `TF-DEMO-A2${n}`, vehiclePlate: 'S 402', faultPercent: 30, agrees: true, injuryDegree: 'MINOR', transportMethod: 'AMBULANCE' }],
      }, 'FIELD'))
      const r = await step<{ reportNumber: string; status: string; parties: { partyNo: number; vehiclePlate: string; faultPercent: number; transportMethod?: string }[] }>(
        'Fault decided from the agreed shares', 'تحديد المسؤولية من النسب المتفق عليها', 'system', () => m('GET', `/v1/accidents/${a.id}`), { note: (x) => `${x.reportNumber} · ${x.status}` })
      show(<>
        <div className="fault">{r.parties.map((p) => <div key={p.partyNo} style={{ flex: p.faultPercent || 1 }} className={p.partyNo === 1 ? 'a' : 'b'}>{p.vehiclePlate} · {p.faultPercent}%</div>)}</div>
        <Kv rows={[['Report', <span className="mono">{r.reportNumber}</span>], ['Status', r.status], ['Party 2', r.parties[1]?.transportMethod ?? '—']]} />
      </>)
    } }] },
  { id: 'permits', inc: '7', icon: <Users size={18} />, en: 'Permits & rental', ar: 'التصاريح والتأجير', uat: 'UAT-2', ids: 'TR-002 · TR-006 · TR-030 · TR-032 · VF-014',
    textEn: 'An instructor permit goes from application to issue with a committee test and a second approver. A radar fine on a rented car goes to the renter, not the company.',
    textAr: 'يمر تصريح المدرب من الطلب إلى الإصدار باختبار لجنة وموافقة ثانية. وتذهب مخالفة الرادار على سيارة مستأجرة إلى المستأجر لا إلى الشركة.',
    actions: [
      { en: 'Issue an instructor permit', ar: 'إصدار تصريح مدرب', tone: 'primary', run: async ({ step, m, c, show }) => {
        const p = await step<{ id: string; number: string }>('Application submitted', 'تقديم الطلب', 'maker', () => m('POST', '/v1/permits', { typeCode: 'DEMO_INSTRUCTOR',
          trafficFileNumber: `TF-DEMO-P${rnd()}`, licenceEligible: true, drivingYears: 6, age: 31, cleanRecordDays: 500, medicallyFit: true, dualControls: false, schoolId: 'SCHOOL-1' }), { note: (r) => r.number })
        const base = `/v1/permits/${p.id}`
        await step('Committee test booked', 'حجز اختبار اللجنة', 'maker', () => m('POST', `${base}/tests`, { committee: 'C1', scheduledAt: iso(DAY) }))
        await step('Test passed (examiner)', 'اجتياز الاختبار', 'checker', () => c('POST', `${base}/test-results`, { examiner: 'EX-1', passed: true }))
        await step('Approved by a second person', 'اعتماد من شخص ثانٍ', 'checker', () => c('POST', `${base}/transitions`, { action: 'approve', reason: 'test passed' }))
        const done = await step<{ number: string; status: string; validTo: string }>('Permit issued', 'إصدار التصريح', 'checker', () => c('POST', `${base}/transitions`, { action: 'issue' }), { note: (r) => `${r.number} · ${r.status}` })
        show(<Kv rows={[['Permit', <span className="mono">{done.number}</span>], ['Status', done.status], ['Valid to', done.validTo]]} />)
      } },
      { en: 'Radar fine on a rented car', ar: 'مخالفة رادار على سيارة مستأجرة', run: async ({ step, m, show }) => {
        const n = rnd(); const plate = `R ${Number(n) % 100000}`; const renter = `TF-DEMO-RENTER${n}`
        const k = await step<{ id: string }>('Rental contract at the airport desk', 'عقد تأجير في مكتب المطار', 'maker', () => m('POST', '/v1/rental-contracts', { companyFile: 'TF-DEMO-RENTAL',
          vehiclePlate: plate, renterFile: renter, startsAt: iso(-6 * HOUR), endsAt: iso(2 * DAY), pickupBranch: 'AIRPORT', returnBranch: 'AIRPORT', type: 'DAILY', bondMinor: 50000 }), { note: () => `${plate} → ${renter}` })
        const v = await step<{ number: string; responsibleFileNumber: string }>('Radar records a fine on the plate', 'الرادار يسجّل مخالفة على اللوحة', 'system', () => m('POST', '/v1/violations', {
          catalogueCode: 'DEMO_PARK', occurredAt: iso(-60_000), region: 'DXB', location: 'Airport Road', plate, ownerFileNumber: 'TF-DEMO-RENTAL', source: 'OFFICER' }, 'FIELD'),
        { note: (r) => `${r.number} → ${r.responsibleFileNumber}` })
        show(<Kv rows={[['Contract', <span className="mono">{k.id.slice(0, 8)}</span>], ['Violation', <span className="mono">{v.number}</span>],
          ['Owner', 'TF-DEMO-RENTAL'], ['Responsible', <b style={{ color: v.responsibleFileNumber === renter ? 'var(--green)' : 'var(--red)' }}>{v.responsibleFileNumber}{v.responsibleFileNumber === renter ? ' ✓ renter' : ''}</b>]]} />)
      } },
    ] },
  { id: 'road', inc: '8', icon: <RouteIcon size={18} />, en: 'Road & marine', ar: 'الطرق والملاحة', ids: 'RE-020 · RE-022 · RE-030 · RE-031',
    textEn: 'An oversize load needs every authority on its route to approve before the permit can be issued. A boat is renewed only after a passed inspection.',
    textAr: 'تحتاج الحمولة الكبيرة موافقة كل جهة على المسار قبل إصدار التصريح. ولا يُجدَّد القارب إلا بعد اجتياز الفحص.',
    actions: [
      { en: 'Oversize load permit', ar: 'تصريح حمولة كبيرة', tone: 'primary', run: async ({ step, m, c, show }) => {
        const r = await step<{ id: string; number: string }>('Haulier applies (route E11)', 'تقديم طلب الناقل (مسار E11)', 'maker', () => m('POST', '/v1/road-permits', { type: 'OVERSIZE',
          applicant: `TF-DEMO-H${rnd()}`, vehicles: ['S 44'], routeSegments: ['DEMO_E11_S1'], region: 'DXB', startsAt: iso(DAY), endsAt: iso(3 * DAY), vehicleLimit: 2,
          requiredAuthorities: ['ROADS', 'POLICE'], widthMetres: 3.4, loadTonnes: 48.0 }, 'PORTAL'), { note: (x) => x.number })
        const base = `/v1/road-permits/${r.id}`
        await step('Issue refused: approvals missing', 'رفض الإصدار: موافقات ناقصة', 'checker', () => c('POST', `${base}/transitions`, { action: 'issue' }), { expectFail: 'any' })
        await step('Roads authority approves', 'موافقة هيئة الطرق', 'checker', () => c('POST', `${base}/approvals`, { authority: 'ROADS', reason: 'route ok' }))
        await step('Police approve', 'موافقة الشرطة', 'checker', () => c('POST', `${base}/approvals`, { authority: 'POLICE' }))
        const done = await step<{ number: string; status: string }>('Permit issued', 'إصدار التصريح', 'checker', () => c('POST', `${base}/transitions`, { action: 'issue' }), { note: (x) => `${x.number} · ${x.status}` })
        show(<Kv rows={[['Permit', <span className="mono">{done.number}</span>], ['Status', done.status], ['Route', 'E11 km 20-40 · 48 t · 3.4 m']]} />)
      } },
      { en: 'Register and renew a boat', ar: 'تسجيل قارب وتجديده', run: async ({ step, m, c, show }) => {
        const hull = `HULL-DEMO-${rnd()}`
        await step('Motor boat registered', 'تسجيل قارب بمحرك', 'maker', () => m('POST', '/v1/marine-craft', { hullId: hull, type: 'MOTOR_BOAT', lengthMetres: 8.5, engines: 2, capacity: 6, homePort: 'DXB_PORT', owner: `TF-DEMO-M${rnd()}` }), { note: () => hull })
        await step('Renewal refused: no inspection', 'رفض التجديد: لا فحص', 'maker', () => m('POST', `/v1/marine-craft/${hull}/renewals`, { validityDays: 365 }), { expectFail: 'any' })
        await step('Inspection passed', 'اجتياز الفحص', 'checker', () => c('POST', `/v1/marine-craft/${hull}/inspections`, { purpose: 'RENEWAL', result: 'PASS', workshop: 'WS_1', documentId: 'DOC-1' }))
        const done = await step<{ status: string; hullId: string }>('Renewed for a year', 'التجديد لسنة', 'maker', () => m('POST', `/v1/marine-craft/${hull}/renewals`, { validityDays: 365 }), { note: (x) => x.status })
        show(<Kv rows={[['Hull', <span className="mono">{done.hullId}</span>], ['Status', done.status], ['Home port', 'DXB_PORT']]} />)
      } },
    ] },
  { id: 'crossborder', inc: '9', icon: <Globe2 size={18} />, en: 'Cross-border', ar: 'التبادل عبر الحدود', uat: 'UAT-5', ids: 'XB-002 · XB-003 · XB-004',
    textEn: 'A partner state sends a violation for a local plate. It is mapped to the local catalogue and lands on the owner’s file to be paid here.',
    textAr: 'ترسل دولة شريكة مخالفة على لوحة محلية، فتُطابق مع الدليل المحلي وتُسجَّل على ملف المالك ليدفعها هنا.',
    actions: [{ en: 'Receive a partner violation', ar: 'استلام مخالفة من دولة شريكة', tone: 'primary', run: async ({ step, m, show, lang }) => {
      let plate = 'S 1'
      try { plate = sessionStorage.getItem('itms.demo.plate') ?? plate } catch { /* ignore */ }
      const ext = `OM-${rnd()}`
      await step('Inbound violation from OMN (OM_SPEED)', 'مخالفة واردة من الدولة الشريكة', 'partner', () => m('POST', '/v1/partner-states/OMN/inbound-violations',
        { externalId: ext, code: 'OM_SPEED', vehiclePlate: plate, currency: 'XTS', amountMinor: 30000, occurredAt: iso(-2 * HOUR) }, 'PARTNER'), { note: () => `${ext} · ${plate}` })
      const list = await step<{ items: { externalId: string; type: string; direction: string; status: string; mappedCode?: string; amountMinor: number; currency?: string; partner: string }[] }>(
        'Exchange log', 'سجل التبادل', 'system', () => m('GET', '/v1/exchange-messages?limit=6'), { note: (x) => `${x.items.length} messages` })
      show(<div className="list">{list.items.slice(0, 6).map((x) => (
        <div className="row" key={x.externalId + x.direction}><div className="ico cyan"><Globe2 size={15} /></div>
          <div className="grow"><div className="title mono" style={{ fontSize: 13 }}>{x.partner} · {x.externalId}</div>
            <div className="meta"><span>{x.direction}</span><span>{x.type}</span>{x.mappedCode && <span>→ {x.mappedCode}</span>}<span>{money(x.amountMinor, x.currency ?? 'XTS', lang)}</span></div></div>
          <span className="pill info">{x.status}</span></div>))}</div>)
    } }] },
]

// ---------- page ----------

export default function Modules() {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [maker, setMaker] = useState(() => load('staff'))
  const [checker, setChecker] = useState(() => load('checker'))
  const [active, setActive] = useState(tours[0].id)
  const [steps, setSteps] = useState<Step[]>([])
  const [result, setResult] = useState<ReactNode>(null)
  const [running, setRunning] = useState(false)
  const [json, setJson] = useState<unknown>(null)
  const [q, setQ] = useState('')
  const [hits, setHits] = useState<{ items: Hit[]; sources: { name: string; status: string }[] } | null>(null)
  const [record, setRecord] = useState<{ hit: Hit; rows: AuditRow[] | null } | null>(null)

  // Record history (CC-121): the audit timeline of a search result, read from the central hash chain as the auditor.
  const openRecord = async (h: Hit) => {
    setRecord({ hit: h, rows: null })
    const type = AUDIT_TYPE[h.type]
    const tryId = (id: string) => call<{ items: AuditRow[] }>('checker', 'GET', `/v1/timelines/${type}/${encodeURIComponent(id)}`, undefined, lang).then((r) => r.items)
    try {
      let rows = type ? await tryId(h.id) : []
      if (type && rows.length === 0 && h.reference !== h.id) rows = await tryId(h.reference)
      setRecord({ hit: h, rows })
    } catch { setRecord({ hit: h, rows: [] }) }
  }

  // Both dev accounts sign in quietly from .env.local (local stack only).
  useEffect(() => {
    const env = import.meta.env
    if (!maker && env.VITE_ITMS_STAFF_PASSWORD) signInStaff('dev.maker', env.VITE_ITMS_STAFF_PASSWORD as string, 'staff').then(setMaker).catch(() => {})
    if (!checker && env.VITE_ITMS_CHECKER_PASSWORD) signInStaff('dev.checker', env.VITE_ITMS_CHECKER_PASSWORD as string, 'checker').then(setChecker).catch(() => {})
  }, [maker, checker])

  const tour = tours.find((t) => t.id === active) ?? tours[0]

  const run = useCallback(async (a: Action) => {
    setRunning(true); setSteps([]); setResult(null); setJson(null)
    let count = 0
    const as = (k: Kind) => <T,>(method: 'GET' | 'POST', path: string, body?: unknown, channel?: string) => call<T>(k, method, path, body, lang, channel)
    const ctx: Ctx = {
      lang,
      m: as('staff'),
      c: as('checker'),
      show: setResult,
      step: async (en, arText, who, fn, opts) => {
        const i = count++
        setSteps((s) => [...s, { en, ar: arText, who, state: 'run' }])
        const set = (patch: Partial<Step>) => setSteps((s) => s.map((x, j) => (j === i ? { ...x, ...patch } : x)))
        await wait(350) // let the audience see each step
        try {
          const r = await fn()
          set({ state: 'ok', note: opts?.note?.(r), json: r })
          setJson(r)
          return r
        } catch (e) {
          const code = e instanceof ApiError ? String(e.problem.code ?? e.status) : ''
          if (opts?.expectFail && (opts.expectFail === 'any' || code === opts.expectFail)) {
            set({ state: 'stop', note: `${code} · ${(e as Error).message}`, json: e instanceof ApiError ? e.problem : undefined })
            if (e instanceof ApiError) setJson(e.problem)
            if (opts.expectFail !== 'any') throw new Stopped()
            return undefined as never
          }
          set({ state: 'fail', note: (e as Error).message })
          throw e
        }
      },
    }
    try {
      await a.run(ctx)
    } catch (e) {
      if (e instanceof Stopped) setResult(<div className="banner info" style={{ margin: 0 }}><ShieldCheck color="var(--cyan)" />
        <div><b>{ar ? 'توقف كما هو مطلوب' : 'Stopped as required'}</b><div className="muted" style={{ fontSize: 13 }}>{ar ? 'لم تُسجَّل أي رسوم.' : 'No fee was charged; nothing was registered.'}</div></div></div>)
    } finally {
      setRunning(false)
    }
  }, [ar, lang])

  const search = async () => {
    if (!q.trim()) return
    try {
      setHits(await call('staff', 'POST', '/v1/searches', { query: q.trim() }, lang))
    } catch (e) {
      setHits({ items: [], sources: [{ name: (e as Error).message, status: 'ERROR' }] })
    }
  }

  const who = { maker: ['dev.maker', 'منشئ'], checker: ['dev.checker', 'مدقق'], partner: ['partner', 'شريك'], system: ['ITMS', 'النظام'] } as const

  return (
    <>
      <div className="searchbar card">
        <Search size={18} className="muted" />
        <input placeholder={ar ? 'بحث موحّد: لوحة، رقم مخالفة، ملف، رقم هيكل…' : 'Federated search: plate, violation, file, VIN…'} value={q}
          onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && search()} aria-label="Search" />
        <span className="pill">Inc 10 · CC-100</span>
        <button className="btn primary" onClick={search} disabled={!maker}>{ar ? 'بحث' : 'Search'}</button>
      </div>
      {hits && (
        <div className="card" style={{ marginBottom: 18 }}>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10 }}>
            {hits.sources.map((s) => <span key={s.name} className={`pill ${s.status === 'OK' ? 'ok' : 'warn'}`}>{s.name} · {s.status}</span>)}
          </div>
          {hits.items.length === 0 ? <div className="empty">{ar ? 'لا نتائج' : 'No results'}</div> : (
            <div className="list">{hits.items.slice(0, 8).map((h) => (
              <div className="row clickable" key={h.type + h.reference} onClick={() => openRecord(h)} role="button" tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && openRecord(h)}><span className="pill info">{h.type}</span>
                <div className="grow"><div className="title mono">{h.reference}</div>
                  <div className="meta">{Object.entries(h.fields).slice(0, 4).map(([k, v]) => <span key={k}>{k}: {String(v)}</span>)}</div></div></div>))}</div>
          )}
        </div>
      )}
      <div className="tour">
        <nav className="tour-nav" aria-label="Modules">
          {tours.map((t) => (
            <button key={t.id} className={`tour-tab ${t.id === active ? 'active' : ''}`} onClick={() => { setActive(t.id); setSteps([]); setResult(null); setJson(null) }}>
              <span className="inc">{t.inc}</span>{t.icon}<span className="grow">{ar ? t.ar : t.en}</span>{t.uat && <span className="pill ok">{t.uat}</span>}<ChevronRight size={14} className="chev" />
            </button>
          ))}
          {[['12', 'Data migration', 'ترحيل البيانات'], ['13', 'AI agents', 'وكلاء الذكاء الاصطناعي'], ['14', 'Hardening & acceptance', 'التحصين والقبول']].map(([n, en, a]) => (
            <div key={n} className="tour-tab future" aria-disabled="true" title={ar ? 'قادم في المستقبل' : 'Coming in future'}>
              <span className="inc">{n}</span><span className="grow">{ar ? a : en}</span><span className="pill future">{ar ? 'قادم' : 'coming'}</span>
            </div>
          ))}
          <div className="who">
            <span className={`pill ${maker ? 'ok' : ''}`}><KeyRound size={12} /> dev.maker</span>
            <span className={`pill ${checker ? 'ok' : ''}`}><UserCheck size={12} /> dev.checker</span>
          </div>
        </nav>
        <section className="card tour-body">
          <div className="tour-head">
            <div className="ico cyan">{tour.icon}</div>
            <div className="grow">
              <h2>{ar ? tour.ar : tour.en} {tour.uat && <span className="pill ok">{tour.uat}</span>}</h2>
              <div className="muted mono" style={{ fontSize: 12 }}>Increment {tour.inc} · {tour.ids}</div>
            </div>
          </div>
          <p className="muted" style={{ lineHeight: 1.6 }}>{ar ? tour.textAr : tour.textEn}</p>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            {tour.actions.map((a) => (
              <button key={a.en} className={`btn ${a.tone === 'primary' ? 'primary' : ''} ${a.tone === 'bad' ? 'danger' : ''}`} disabled={running || !maker || !checker} onClick={() => run(a)}>
                {ar ? a.ar : a.en}
              </button>
            ))}
          </div>
          {steps.length > 0 && (
            <ol className="timeline">
              {steps.map((s, i) => (
                <li key={i} className={s.state}>
                  <span className="dotx">{s.state === 'run' ? <Loader2 size={15} className="spin" /> : s.state === 'ok' ? <CheckCircle2 size={15} /> : s.state === 'stop' ? <ShieldCheck size={15} /> : s.state === 'fail' ? <XCircle size={15} /> : <CircleDashed size={15} />}</span>
                  <div className="grow"><div className="title">{ar ? s.ar : s.en}</div>{s.note && <div className="meta">{s.note}</div>}</div>
                  <span className={`pill ${s.who === 'checker' ? 'info' : ''}`}>{ar ? who[s.who][1] : who[s.who][0]}</span>
                </li>
              ))}
            </ol>
          )}
          {result && <div className="tour-result">{result}</div>}
          {json !== null && (
            <details className="json"><summary><Code2 size={14} /> {ar ? 'آخر استجابة API' : 'Last API response'}</summary><pre>{JSON.stringify(json, null, 2)}</pre></details>
          )}
        </section>
      </div>
      <Drawer open={!!record} onClose={() => setRecord(null)} title={record ? `${record.hit.type} · ${record.hit.reference}` : ''}>
        {record && (
          <>
            <dl className="kv" style={{ marginTop: 0 }}>
              {Object.entries(record.hit.fields).slice(0, 8).map(([k, v]) => <Fragment key={k}><dt>{k}</dt><dd>{String(v)}</dd></Fragment>)}
            </dl>
            <h3 className="drawer-sub">{ar ? 'سجل التدقيق (السلسلة المركزية)' : 'Audit history (central hash chain) · CC-121'}</h3>
            {record.rows === null ? <Skeleton /> : record.rows.length === 0 ? <div className="empty">{ar ? 'لا سجل لهذا النوع بعد' : 'No history recorded for this record type yet'}</div> : (
              <Timeline items={record.rows.map((r) => ({
                title: r.action.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()),
                meta: <><span>{r.service.replace('-service', '')}</span><span>{r.actorUsername ?? r.actorId}</span><span>{r.channel}</span><span>{when(r.occurredAt, lang)}</span><span className="mono">#{r.seq} {r.hash.slice(0, 8)}…</span></>,
                tone: /CANCEL|REJECT|HOLD_PLACED|SUSPEN/.test(r.action) ? 'bad' as const : /PAID|LIFTED|CONFIRMED|APPROVED|REGISTERED/.test(r.action) ? 'ok' as const : 'info' as const,
              }))} />
            )}
          </>
        )}
      </Drawer>
    </>
  )
}

