import { useEffect, useMemo, useState } from 'react'
import { BadgeCheck, BookOpen, Clock, FileText, Search, ShieldCheck, Users, Wallet } from 'lucide-react'
import { ApiError, call, money } from '../api'
import { load, signInStaff, type Kind } from '../auth'
import { Card, Skeleton } from '../components'
import { useI18n } from '../i18n'
import { nameOf } from '../names'
import { RuleList } from '../rules'

// Service guide (demo preview of CH-008): before starting a service, see its fee (a live quote from fees-service),
// the documents it needs, the time it takes (SLA) and who can use it, all from the service catalogue
// (transaction-service). The catalogue needs a signed-in user (the citizen, else the demo officer); certificate
// verification (CC-083) is public and needs no sign-in. The demo changes no requirement status (CH-008, CH-010 stay
// as the ledger says).

type Spec = {
  code: string; name: { en: string; nl: string }; module: string; customerTypes?: string[]; channels?: string[]
  requiredDocuments?: string[]; feeCode?: string | null; sla?: string; regions?: string[]; anyRegion?: boolean
}
type Def = { definition: Spec; status?: string }
type Quote = { lines: { kind: string; nameEn?: string; nameNl?: string; feeCode?: string; amountMinor?: number; amount?: number }[]; total?: number; totalMinor?: number; currency?: string }
type Verification = { number: string; titleEn: string; titleNl: string; holder: string; validity: string; issuedOn: string; validUntil?: string | null }

// ISO 8601 SLA (P5D, PT4H, PT30M) in words.
function slaText(sla: string | undefined, ar: boolean) {
  const m = sla?.match(/^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?$/)
  if (!m) return '—'
  const [, d, h, mi] = m
  const parts = [d && `${d} ${ar ? 'يوم' : Number(d) === 1 ? 'day' : 'days'}`, h && `${h} ${ar ? 'ساعة' : 'h'}`, mi && `${mi} ${ar ? 'دقيقة' : 'min'}`].filter(Boolean)
  return parts.join(' ') || '—'
}

export default function Guide() {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const kind: Kind = load('customer') ? 'customer' : 'staff'
  const [ready, setReady] = useState(kind === 'customer' || !!load('staff'))
  const [defs, setDefs] = useState<Spec[] | null>(null)
  const [q, setQ] = useState('')
  const [pick, setPick] = useState<Spec | null>(null)
  const [quote, setQuote] = useState<Quote | null | 'none'>(null)
  const [cert, setCert] = useState({ number: '', code: '' })
  const [ver, setVer] = useState<Verification | string | null>(null)

  useEffect(() => {
    const pw = import.meta.env.VITE_ITMS_STAFF_PASSWORD as string | undefined
    if (!ready && pw) signInStaff('dev.maker', pw, 'staff').then(() => setReady(true)).catch(() => {})
  }, [ready])
  useEffect(() => {
    if (!ready) return
    call<{ items: Def[] }>(kind, 'GET', '/v1/services', undefined, lang)
      .then((r) => setDefs(r.items.map((d) => d.definition).filter((s) => !/^(SMOKE|SMK|TEST|DEMO_SVC_)/.test(s.code)).sort((a, b) => a.name.en.localeCompare(b.name.en))))
      .catch(() => setDefs([]))
  }, [ready, kind, lang])

  const open = async (s: Spec) => {
    setPick(s); setQuote(null)
    try {
      setQuote(await call<Quote>(kind, 'POST', '/v1/quotes', { serviceCode: s.code, region: s.anyRegion || !s.regions?.length || s.regions[0] === '*' ? 'DXB' : s.regions[0], customerCategory: 'CITIZEN' }, lang))
    } catch { setQuote('none') }
  }
  const verify = async () => {
    setVer(null)
    const r = await fetch(`/v1/certificate-verifications/${encodeURIComponent(cert.number.trim())}?code=${encodeURIComponent(cert.code.trim())}`)
    const body = await r.json().catch(() => ({}))
    setVer(r.ok ? body as Verification : (body.detail ?? `HTTP ${r.status}`))
  }

  const shown = useMemo(() => (defs ?? []).filter((s) => !q || `${s.code} ${s.name.en} ${s.name.nl} ${s.module}`.toLowerCase().includes(q.toLowerCase())), [defs, q])
  const total = (x: Quote) => x.totalMinor ?? x.total ?? x.lines.reduce((n, l) => n + (l.amountMinor ?? l.amount ?? 0), 0)
  const certRules = [
    { ok: /^[A-Za-z0-9-]{4,40}$/.test(cert.number.trim()), en: 'Certificate number as printed', ar: 'رقم الشهادة كما هو مطبوع' },
    { ok: /^[A-Za-z0-9]{10}$/.test(cert.code.trim()), en: 'Verification code: 10 letters or digits', ar: 'رمز التحقق: 10 حروف أو أرقام' },
  ]

  return (
    <div className="grid g-2" style={{ gridAutoFlow: 'row dense' }}>
      <Card title={ar ? 'دليل الخدمات' : 'Service guide'} icon={<BookOpen size={16} />} right={<span className="pill">CH-008 · {ar ? 'معاينة' : 'preview'}</span>}>
        <div className="req-filters"><input placeholder={ar ? 'ابحث عن خدمة' : 'Find a service'} value={q} onChange={(e) => setQ(e.target.value)} aria-label="Find a service" /></div>
        {defs === null ? <Skeleton lines={5} /> : shown.length === 0 ? <div className="empty">{ar ? 'لا خدمات' : 'No services'}</div> : (
          <div className="list" style={{ maxHeight: 520, overflow: 'auto' }}>{shown.map((s) => (
            <button key={s.code} className={`row clickable ${pick?.code === s.code ? 'active' : ''}`} style={{ textAlign: 'start', width: '100%', background: 'none', border: 0, color: 'inherit', font: 'inherit' }} onClick={() => open(s)}>
              <div className="grow"><div className="title">{ar ? s.name.nl : s.name.en}</div>
                <div className="meta"><span>{nameOf(s.module, lang)}</span><span className="mono">{s.code}</span><span><Clock size={11} /> {slaText(s.sla, ar)}</span></div></div>
            </button>))}</div>
        )}
      </Card>

      <Card title={pick ? (ar ? pick.name.nl : pick.name.en) : (ar ? 'قبل أن تبدأ' : 'Before you start')} icon={<FileText size={16} />}>
        {!pick ? <div className="empty">{ar ? 'اختر خدمة لترى رسومها ومستنداتها ومدتها ومن يحق له استخدامها.' : 'Pick a service to see its fee, documents, time and who can use it.'}</div> : (
          <dl className="kv" style={{ marginTop: 0 }}>
            <dt><Wallet size={13} /> {ar ? 'الرسوم' : 'Fee'}</dt>
            <dd>{quote === null ? '…' : quote === 'none' ? (ar ? 'لا رسوم مُعدّة لهذه الخدمة' : 'No fee configured for this service') : (
              <><b>{money(total(quote), quote.currency ?? 'AED', lang)}</b>{quote.lines.map((l, i) => <div key={i} className="muted" style={{ fontSize: 12.5 }}>{(ar ? l.nameNl : l.nameEn) ?? l.feeCode} · {money(l.amountMinor ?? l.amount ?? 0, quote.currency ?? 'AED', lang)}</div>)}</>)}</dd>
            <dt><FileText size={13} /> {ar ? 'المستندات المطلوبة' : 'Documents needed'}</dt>
            <dd>{pick.requiredDocuments?.length ? pick.requiredDocuments.map((d) => <span key={d} className="pill" style={{ marginInlineEnd: 4 }}>{nameOf(d, lang)}</span>) : (ar ? 'لا شيء' : 'None')}</dd>
            <dt><Clock size={13} /> {ar ? 'مدة الإنجاز' : 'Time to complete'}</dt><dd>{slaText(pick.sla, ar)}</dd>
            <dt><Users size={13} /> {ar ? 'من يحق له' : 'Who can use it'}</dt>
            <dd>{(pick.customerTypes ?? []).map((c) => nameOf(c, lang)).join(', ') || '—'}</dd>
            <dt>{ar ? 'القنوات' : 'Channels'}</dt><dd>{(pick.channels ?? []).map((c) => nameOf(c, lang)).join(', ') || '—'}</dd>
            <dt>{ar ? 'المناطق' : 'Regions'}</dt><dd>{pick.anyRegion ? (ar ? 'أي منطقة' : 'Any region') : (pick.regions ?? []).join(', ') || '—'}</dd>
          </dl>
        )}
        <p className="muted" style={{ fontSize: 12, marginBottom: 0 }}>{ar ? 'من دليل الخدمات وخدمة الرسوم مباشرة؛ الرسوم عرض سعر للمواطن في دبي.' : 'Live from the service catalogue and the fee service; the fee is a quote for a citizen in Dubai.'}</p>
      </Card>

      <Card title={ar ? 'التحقق من شهادة' : 'Verify a certificate'} icon={<BadgeCheck size={16} />} right={<span className="pill">CC-083 · {ar ? 'عام' : 'public'}</span>}>
        <p className="muted" style={{ marginTop: 0, fontSize: 13 }}>{ar ? 'لا يحتاج إلى تسجيل دخول. أدخل رقم الشهادة والرمز المطبوع عليها.' : 'No sign-in needed. Enter the number and the code printed on the certificate.'}</p>
        <div className="pf-grid">
          <label className="field"><span>{ar ? 'رقم الشهادة' : 'Certificate number'}</span><input value={cert.number} onChange={(e) => setCert({ ...cert, number: e.target.value })} dir="ltr" /></label>
          <label className="field"><span>{ar ? 'رمز التحقق' : 'Verification code'}</span><input value={cert.code} onChange={(e) => setCert({ ...cert, code: e.target.value })} maxLength={10} dir="ltr" /></label>
        </div>
        <RuleList rules={certRules} lang={lang} />
        <button className="btn primary" disabled={!certRules.every((r) => r.ok)} onClick={() => verify().catch((e) => setVer(e instanceof ApiError ? e.message : String(e)))}><Search size={15} /> {ar ? 'تحقق' : 'Verify'}</button>
        {ver && (typeof ver === 'string' ? <p role="alert" style={{ color: 'var(--red)', fontSize: 13 }}>{ver}</p> : (
          <div className={`banner ${ver.validity === 'VALID' ? 'info' : ''}`} style={{ marginTop: 10 }}><ShieldCheck color={ver.validity === 'VALID' ? 'var(--green)' : 'var(--red)'} />
            <div><b>{ar ? ver.titleNl : ver.titleEn} · {ver.validity}</b><div className="muted" style={{ fontSize: 13 }}>{ver.number} · {ver.holder} · {ver.issuedOn}{ver.validUntil ? ` → ${ver.validUntil}` : ''}</div></div></div>))}
      </Card>
    </div>
  )
}
