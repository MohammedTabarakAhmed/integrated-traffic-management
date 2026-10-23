import { useEffect, useMemo, useState } from 'react'
import { BookOpen, Calculator, CheckCircle2, KeyRound, ListTree, Scale, UserCheck, ListChecks } from 'lucide-react'
import { ApiError, call, money, when } from '../api'
import { autoSignInStaff, load } from '../auth'
import { Card, Skeleton, StatusPill, tabFromLink } from '../components'
import { useI18n } from '../i18n'
import Access from './Access'

// Administration (demo D4.6): the configuration the platform runs on, readable by testers. Violation catalogue
// (VF-001..VF-004), reference lists (CC-010), fee quotes (CC-040) and service-catalogue changes under maker-checker
// (CC-020, CC-114): the maker (dev.maker) proposes, a different person (dev.checker) approves.

type Entry = { code: string; versionNo: number; descriptionEn: string; descriptionNl: string; fineMinor: number; blackPoints: number; legalArticle: string; effectiveFrom: string; impoundDays: number; courtReferral: boolean }
type RefList = { code: string; name: { nl: string; en: string } }
type RefItem = { code: string; name?: { nl: string; en: string }; active?: boolean; [k: string]: unknown }
type Line = { kind: string; feeCode?: string; nameEn?: string; nameNl?: string; amount?: number; amountMinor?: number; total?: number }
type Quote = { id: string; lines: Line[]; total?: number; totalMinor?: number; currency?: string }
type Change = { id: string; changeType: string; serviceCode?: string | null; status: string; maker?: { username?: string } | string | null; checker?: { username?: string } | string | null; submittedAt: string; reason: string }
type Tab = 'catalogue' | 'lists' | 'fees' | 'changes' | 'access'

const who = (x: Change['maker']) => (typeof x === 'string' ? x : x?.username) ?? '—'
const isTest = (code: string) => /^(SMK|SMKP|PT_|TEST)/.test(code)

export default function Admin({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [tab, setTab] = useState<Tab>(() => tabFromLink<Tab>(['catalogue', 'lists', 'fees', 'changes', 'access'], 'catalogue'))
  const [ready, setReady] = useState(() => !!load('staff') && !!load('checker'))
  const [cat, setCat] = useState<Entry[] | null>(null)
  const [showTest, setShowTest] = useState(false)
  const [lists, setLists] = useState<RefList[] | null>(null)
  const [list, setList] = useState<string | null>(null)
  const [items, setItems] = useState<RefItem[] | null>(null)
  const [quote, setQuote] = useState<Quote | null>(null)
  const [q, setQ] = useState({ serviceCode: 'VEH_FIRST_REG', region: 'DXB', customerCategory: 'CITIZEN', subjectCategory: 'PRIVATE' })
  const [changes, setChanges] = useState<Change[] | null>(null)

  useEffect(() => {
    const env = import.meta.env
    const tasks: Promise<unknown>[] = []
    if (!load('staff') && env.VITE_ITMS_STAFF_PASSWORD) tasks.push(autoSignInStaff('dev.maker', env.VITE_ITMS_STAFF_PASSWORD as string, 'staff') as Promise<unknown>)
    if (!load('checker') && env.VITE_ITMS_CHECKER_PASSWORD) tasks.push(autoSignInStaff('dev.checker', env.VITE_ITMS_CHECKER_PASSWORD as string, 'checker') as Promise<unknown>)
    Promise.all(tasks).then(() => setReady(true)).catch(() => {})
  }, [])

  useEffect(() => {
    if (!ready) return
    call<{ items: Entry[] }>('staff', 'GET', '/v1/violation-catalogue', undefined, lang).then((r) => setCat(r.items)).catch(() => setCat([]))
    call<{ items: RefList[] }>('staff', 'GET', '/v1/reference-lists', undefined, lang).then((r) => { setLists(r.items); if (r.items[0]) setList(r.items[0].code) }).catch(() => setLists([]))
    call<{ items: Change[] }>('staff', 'GET', '/v1/catalogue-changes?limit=200', undefined, lang).then((r) => setChanges(r.items)).catch(() => setChanges([]))
  }, [ready, lang])

  useEffect(() => {
    if (!list) return
    setItems(null)
    call<{ items: RefItem[] }>('staff', 'GET', `/v1/reference-lists/${list}/items`, undefined, lang).then((r) => setItems(r.items)).catch(() => setItems([]))
  }, [list, lang])

  const shown = useMemo(() => (cat ?? []).filter((e) => showTest || !isTest(e.code)).sort((a, b) => a.code.localeCompare(b.code)), [cat, showTest])

  const runQuote = async () => {
    try { setQuote(await call<Quote>('staff', 'POST', '/v1/quotes', q, lang)) } catch (e) { setQuote(null); notify(e instanceof ApiError ? e.message : String(e), true) }
  }
  // Maker proposes a new service; it stays PENDING until a different person approves it.
  const propose = async () => {
    const code = `DEMO_SVC_${String(Date.now()).slice(-6)}`
    const service = { code, name: { nl: 'خدمة تجريبية', en: 'Demo service' }, module: 'LICENSING', customerTypes: ['INDIVIDUAL'], channels: ['PORTAL', 'BACKOFFICE'],
      requiredDocuments: [], sla: 'P3D', regions: ['*'], anyRegion: true, allowsConcurrentOpen: false }
    try {
      const c = await call<Change>('staff', 'POST', '/v1/catalogue-changes', { changeType: 'CREATE_SERVICE', serviceCode: code, service, reason: 'new demo service' }, lang)
      setChanges((cs) => [c, ...(cs ?? [])]); notify(ar ? `اقتراح ${code} بانتظار المدقق` : `${code} proposed by dev.maker, waiting for a checker`)
    } catch (e) { notify(e instanceof ApiError ? e.message : String(e), true) }
  }
  const selfApprove = async (c: Change) => {
    try { await call('staff', 'POST', `/v1/catalogue-changes/${c.id}/approvals`, {}, lang); notify('approved?!', true) }
    catch (e) { notify(`${ar ? 'رفض صحيح' : 'Refused as expected'}: ${e instanceof ApiError ? e.message : String(e)}`) }
  }
  const approve = async (c: Change) => {
    try { await call('checker', 'POST', `/v1/catalogue-changes/${c.id}/approvals`, {}, lang); notify(ar ? 'تم الاعتماد من المدقق' : 'Approved by the checker')
      setChanges((cs) => cs?.map((x) => (x.id === c.id ? { ...x, status: 'APPROVED' } : x)) ?? null) } catch (e) { notify(e instanceof ApiError ? e.message : String(e), true) }
  }
  const lineAmount = (l: Line) => l.amountMinor ?? l.amount ?? l.total ?? 0

  if (!ready) return <div className="card empty">{ar ? 'جارٍ الدخول…' : 'Signing in…'}</div>
  const tabs: [Tab, string, React.ReactNode][] = [
    ['catalogue', ar ? 'دليل المخالفات' : 'Violation catalogue', <Scale size={15} key="a" />],
    ['lists', ar ? 'القوائم المرجعية' : 'Reference lists', <ListTree size={15} key="b" />],
    ['fees', ar ? 'حاسبة الرسوم' : 'Fee calculator', <Calculator size={15} key="c" />],
    ['changes', ar ? 'المنشئ والمدقق' : 'Maker-checker', <UserCheck size={15} key="d" />],
    ['access', ar ? 'الصلاحيات' : 'Access', <KeyRound size={15} key="e" />],
  ]
  return (
    <>
      <div className="seg" role="tablist">{tabs.map(([k, label, icon]) => (
        <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{icon}{label}</button>))}</div>

      {tab === 'access' && ready && <Access />}
      {tab === 'catalogue' && (
        <Card title={ar ? 'دليل المخالفات (إصدارات سارية)' : 'Violation catalogue (versions in force)'} icon={<BookOpen size={16} />}
          right={<label className="toggle"><input type="checkbox" checked={showTest} onChange={(e) => setShowTest(e.target.checked)} /> {ar ? 'إظهار بيانات الاختبار' : 'show test entries'}</label>}>
          {cat === null ? <Skeleton lines={5} /> : (
            <div className="table-wrap"><table className="tbl">
              <thead><tr><th>{ar ? 'الرمز' : 'Code'}</th><th>{ar ? 'الوصف' : 'Description'}</th><th>{ar ? 'المادة' : 'Article'}</th><th>{ar ? 'الغرامة' : 'Fine'}</th><th>{ar ? 'النقاط' : 'Points'}</th><th>{ar ? 'إصدار' : 'Ver.'}</th><th>{ar ? 'ساري من' : 'From'}</th></tr></thead>
              <tbody>{shown.map((e) => (
                <tr key={e.code + e.versionNo}>
                  <td className="mono">{e.code}</td><td>{ar ? e.descriptionNl : e.descriptionEn}</td><td>{e.legalArticle}</td>
                  <td className="num">{money(e.fineMinor, 'AED', lang)}</td><td className="num">{e.blackPoints > 0 ? <span className="pill warn">+{e.blackPoints}</span> : '—'}</td>
                  <td className="num">v{e.versionNo}</td><td>{e.effectiveFrom}</td>
                </tr>))}</tbody>
            </table></div>
          )}
          <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{shown.length} {ar ? 'مدخل' : 'entries'} · VF-001…VF-004 · {ar ? 'التغيير بإصدار جديد ولا يبدأ في الماضي' : 'changes are new versions and never start in the past'}</p>
        </Card>
      )}

      {tab === 'lists' && (
        <div className="grid g-3">
          <Card title={ar ? 'القوائم' : 'Lists'} icon={<ListTree size={16} />}>
            {lists === null ? <Skeleton /> : <div className="list">{lists.map((l) => (
              <div key={l.code} className={`row clickable ${list === l.code ? 'sel' : ''}`} onClick={() => setList(l.code)}><div className="grow"><div className="title">{ar ? l.name.nl : l.name.en}</div><div className="meta mono">{l.code}</div></div></div>))}</div>}
          </Card>
          <Card className="span-2" title={list ?? ''} icon={<BookOpen size={16} />} right={<span className="pill">CC-010</span>}>
            {items === null ? <Skeleton /> : items.length === 0 ? <div className="empty">—</div> : (
              <div className="table-wrap"><table className="tbl"><thead><tr><th>{ar ? 'الرمز' : 'Code'}</th><th>{ar ? 'الاسم' : 'Name'}</th><th>{ar ? 'الحالة' : 'Status'}</th></tr></thead>
                <tbody>{items.map((i) => <tr key={i.code}><td className="mono">{i.code}</td><td>{i.name ? (ar ? i.name.nl : i.name.en) : '—'}</td><td>{i.active === false ? <StatusPill s="CANCELLED" /> : <StatusPill s="ACTIVE" />}</td></tr>)}</tbody></table></div>
            )}
          </Card>
        </div>
      )}

      {tab === 'fees' && (
        <div className="grid g-2">
          <Card title={ar ? 'حساب الرسوم' : 'Quote a service fee'} icon={<Calculator size={16} />} right={<span className="pill">CC-040</span>}>
            {([['serviceCode', ar ? 'الخدمة' : 'Service', ['VEH_FIRST_REG']], ['region', ar ? 'المنطقة' : 'Region', ['DXB', 'AUH', 'SHJ']],
              ['customerCategory', ar ? 'فئة المتعامل' : 'Customer category', ['CITIZEN', 'RESIDENT', 'VISITOR', 'COMPANY']],
              ['subjectCategory', ar ? 'فئة المركبة' : 'Vehicle category', ['PRIVATE', 'COMMERCIAL', 'MOTORCYCLE']]] as [keyof typeof q, string, string[]][]).map(([k, label, opts]) => (
              <label key={k} className="field"><span>{label}</span>
                <select value={q[k]} onChange={(e) => setQ({ ...q, [k]: e.target.value })}>{opts.map((o) => <option key={o}>{o}</option>)}</select></label>))}
            <button className="btn primary" onClick={runQuote}><Calculator size={15} /> {ar ? 'احسب' : 'Calculate'}</button>
          </Card>
          <Card title={ar ? 'عرض السعر' : 'Quote'} icon={<CheckCircle2 size={16} />}>
            {!quote ? <div className="empty">{ar ? 'اختر الخيارات واضغط احسب' : 'Choose the options and press Calculate'}</div> : (
              <>
                <div className="list">{quote.lines.map((l, i) => (
                  <div className="row" key={i}><span className="pill">{l.kind}</span><div className="grow"><div className="title">{(ar ? l.nameNl : l.nameEn) ?? l.feeCode}</div><div className="meta mono">{l.feeCode}</div></div><b>{money(lineAmount(l), quote.currency ?? 'AED', lang)}</b></div>))}</div>
                <div className="quote-total"><span>{ar ? 'الإجمالي' : 'Total'}</span><b className="grad-text">{money(quote.totalMinor ?? quote.total ?? quote.lines.reduce((s, l) => s + lineAmount(l), 0), quote.currency ?? 'AED', lang)}</b></div>
                <p className="muted mono" style={{ fontSize: 11.5 }}>quote {quote.id}</p>
              </>
            )}
          </Card>
        </div>
      )}

      {tab === 'changes' && (
        <Card title={ar ? 'تغييرات دليل الخدمات (منشئ ← مدقق)' : 'Service-catalogue changes (maker → checker)'} icon={<UserCheck size={16} />}
          right={<span style={{ display: 'flex', gap: 8, alignItems: 'center' }}><span className="pill">CC-020 · CC-114</span><button className="btn primary" onClick={propose}>{ar ? 'اقتراح خدمة جديدة' : 'Propose a new service'}</button></span>}>
          {changes === null ? <Skeleton lines={5} /> : (
            <div className="table-wrap"><table className="tbl">
              <thead><tr><th>{ar ? 'الخدمة' : 'Service'}</th><th>{ar ? 'التغيير' : 'Change'}</th><th>{ar ? 'المنشئ' : 'Maker'}</th><th>{ar ? 'المدقق' : 'Checker'}</th><th>{ar ? 'التاريخ' : 'Submitted'}</th><th>{ar ? 'الحالة' : 'Status'}</th><th /></tr></thead>
              <tbody>{changes.filter((c) => showTest || !isTest(c.serviceCode ?? '')).slice(0, 25).map((c) => (
                <tr key={c.id}><td className="mono">{c.serviceCode ?? '—'}</td><td>{c.changeType}</td><td>{who(c.maker)}</td><td>{who(c.checker)}</td><td>{when(c.submittedAt, lang)}</td><td><StatusPill s={c.status} /></td>
                  <td style={{ whiteSpace: 'nowrap' }}>{c.status === 'PENDING_APPROVAL' && <>
                    <button className="btn ghost" onClick={() => selfApprove(c)} title={ar ? 'يجب أن يُرفض' : 'should be refused'}>{ar ? 'اعتماد كمنشئ' : 'Approve as maker'}</button>
                    <button className="btn" onClick={() => approve(c)}>{ar ? 'اعتماد كمدقق' : 'Approve as checker'}</button></>}</td></tr>))}</tbody>
            </table></div>
          )}
          <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{ar ? 'لا يعتمد المنشئ تغييره بنفسه؛ يقوم بذلك شخص آخر.' : 'A maker never approves their own change; a second person does.'}</p>
        </Card>
      )}

      <section className="card req-progress page-enter" style={{ gridColumn: '1 / -1' }}>
        <div className="req-progress-head">
          <ListChecks size={16} />
          <span>{ar ? 'متطلبات الإدارة' : 'Administration requirements'}</span>
          <span className="pill ok">{ar ? 'مباشر' : 'live'}</span>
        </div>
        <div className="req-progress-items">
          {[
            { id: 'VF-001', en: 'Violation catalogue with versions and regional variants', ar: 'دليل المخالفات بإصداراته ومتغيراته الإقليمية' },
            { id: 'VF-002', en: 'Fine amount, points, legal article, impound days, court referral', ar: 'مبلغ الغرامة، النقاط، المادة القانونية، أيام الحجز، إحالة للمحكمة' },
            { id: 'VF-003', en: 'Repeat offence multiplier; speed band calculation', ar: 'مضاعف المخالفة المتكررة؛ حساب شريحة السرعة' },
            { id: 'VF-004', en: 'Effective from date; never retroactive', ar: 'تاريخ السريان؛ لا رجعية أبداً' },
            { id: 'CC-010', en: 'Bilingual reference lists with effective dates and external codes', ar: 'قوائم مرجعية ثنائية اللغة مع تواريخ السريان والرموز الخارجية' },
            { id: 'CC-114', en: 'Reference data changes through maker-checker', ar: 'تغييرات البيانات المرجعية عبر المنشئ والمدقق' },
            { id: 'CC-020', en: 'Service catalogue with code, names, module, channels, documents, fees, rules, workflow, SLA, regions', ar: 'دليل الخدمات بالرمز، الأسماء، الوحدة، القنوات، المستندات، الرسوم، القواعد، سير العمل، SLA، المناطق' },
            { id: 'CC-040', en: 'Fee per service, region, customer category, vehicle category, effective dates', ar: 'رسوم لكل خدمة، منطقة، فئة متعامل، فئة مركبة، تواريخ سريان' },
            { id: 'CC-046', en: 'Fee changes need maker-checker; never retroactive', ar: 'تغييرات الرسوم تحتاج منشئ ومدقق؛ لا رجعية' },
            { id: 'CC-047', en: 'Itemised fee quote before paying', ar: 'عرض سعر مفصل قبل الدفع' },
            { id: 'CC-001', en: 'RBAC with region/centre/module data scopes', ar: 'RBAC مع نطاقات بيانات المنطقة/المركز/الوحدة' },
            { id: 'CC-002', en: 'Segregation of duties (maker ≠ checker)', ar: 'فصل الواجبات (المنشئ ≠ المدقق)' },
            { id: 'CC-005', en: 'Roles created/changed at runtime without code', ar: 'أدوار تُنشأ/تتغير وقت التشغيل دون كود' },
            { id: 'CC-110', en: 'External partner codes on reference items', ar: 'رموز الشركاء الخارجيين على عناصر المرجع' },
            { id: 'CC-112', en: 'Regional variants override national items', ar: 'المتغيرات الإقليمية تتجاوز العناصر الوطنية' },
          ].map((r) => (
            <div key={r.id} className="req-progress-row">
              <span className="req-progress-id mono" style={{ color: 'var(--uae-green)' }}>{r.id}</span>
              <span className="req-progress-text" style={{ flex: 1 }}>{ar ? r.ar : r.en}</span>
              <span className="pill ok">{ar ? 'مبني' : 'built'}</span>
            </div>
          ))}
        </div>
      </section>
    </>
  )
}
