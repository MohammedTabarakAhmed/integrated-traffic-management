import { useEffect, useMemo, useState } from 'react'
import { BadgeCheck, BookOpen, Check, Clock, FileText, Link2, Search, ShieldAlert, ShieldCheck, ShieldX, Stamp, Users, Wallet } from 'lucide-react'
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
// A sample template the demo publishes once (dev.checker) and issues from (dev.maker), so a tester has a real
// certificate to verify. Issued certificates are real records of document-service, titled as samples.
const SAMPLE_TEMPLATE = {
  code: 'DEMO_CERT_SAMPLE', titleNl: 'شهادة تجريبية', titleEn: 'Sample certificate (demo)',
  bodyNl: 'شهادة تجريبية صادرة باسم {{holderNameNl}} لعرض التحقق العام.', bodyEn: 'Sample certificate issued to {{holderNameEn}} to show public verification.',
  validityDays: 30, reason: 'demo portal sample certificate',
}
// The QR on a certificate carries <base>/v1/certificate-verifications/<number>?code=<code>; a pasted link or a portal
// link (#/guide?number=..&code=..) fills the form.
function fromLink(text: string): { number: string; code: string } | null {
  const m = text.match(/certificate-verifications\/([A-Za-z0-9-]+)\?code=([A-Za-z0-9]+)/)
  return m ? { number: m[1], code: m[2] } : null
}
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
  const [ver, setVer] = useState<Verification | { status: number; text: string } | null>(null)
  const [verifying, setVerifying] = useState(false)
  const [issuing, setIssuing] = useState(false)
  const [issueErr, setIssueErr] = useState('')
  const [copied, setCopied] = useState(false)

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
  // Public check: plain fetch, no token (CC-083).
  const verify = async (c = cert) => {
    setVer(null); setVerifying(true)
    try {
      const r = await fetch(`/v1/certificate-verifications/${encodeURIComponent(c.number.trim())}?code=${encodeURIComponent(c.code.trim())}`,
        { headers: { 'Accept-Language': lang } })
      const body = await r.json().catch(() => ({}))
      setVer(r.ok ? body as Verification : { status: r.status, text: body.detail ?? `HTTP ${r.status}` })
    } catch (e) { setVer({ status: 0, text: (e as Error).message }) } finally { setVerifying(false) }
  }
  // A deep link (#/guide?number=..&code=..) verifies straight away.
  useEffect(() => {
    const q = new URLSearchParams(location.hash.split('?')[1] ?? '')
    const c = { number: q.get('number') ?? '', code: q.get('code') ?? '' }
    if (c.number && c.code) { setCert(c); void verify(c) }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps
  const setNumber = (v: string) => { const l = fromLink(v); if (l) setCert(l); else setCert({ ...cert, number: v }) }
  // Staff: issue a sample certificate (publishing the sample template first if this stack has none), then verify it.
  const issueSample = async () => {
    setIssuing(true); setIssueErr('')
    try {
      const env = import.meta.env
      if (!load('staff') && env.VITE_ITMS_STAFF_PASSWORD) await signInStaff('dev.maker', env.VITE_ITMS_STAFF_PASSWORD as string, 'staff')
      const body = { templateCode: SAMPLE_TEMPLATE.code, holderNameNl: 'حامل تجريبي', holderNameEn: 'Sample Holder', subjectType: 'TRAFFIC_FILE', subjectId: 'DEMO-SAMPLE' }
      const issue = () => call<{ number: string; verificationCode: string }>('staff', 'POST', '/v1/certificates', body, lang)
      let c
      try { c = await issue() } catch (e) {
        if (!(e instanceof ApiError && e.problem.code === 'CERTIFICATE_TEMPLATE_NOT_FOUND')) throw e
        if (!load('checker') && env.VITE_ITMS_CHECKER_PASSWORD) await signInStaff('dev.checker', env.VITE_ITMS_CHECKER_PASSWORD as string, 'checker')
        await call('checker', 'POST', '/v1/certificate-templates', SAMPLE_TEMPLATE, lang)
        c = await issue()
      }
      const next = { number: c.number, code: c.verificationCode }
      setCert(next); await verify(next)
    } catch (e) { setIssueErr(e instanceof Error ? e.message : String(e)) } finally { setIssuing(false) }
  }
  const shareLink = async () => {
    try {
      await navigator.clipboard.writeText(`${location.origin}/#/guide?number=${encodeURIComponent(cert.number.trim())}&code=${encodeURIComponent(cert.code.trim())}`)
      setCopied(true); setTimeout(() => setCopied(false), 1600)
    } catch { /* clipboard blocked */ }
  }
  const staffCanIssue = !!load('staff') || !!import.meta.env.VITE_ITMS_STAFF_PASSWORD

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
          <label className="field"><span>{ar ? 'رقم الشهادة' : 'Certificate number'}</span><input value={cert.number} onChange={(e) => setNumber(e.target.value)} placeholder={ar ? 'أو الصق رابط رمز QR' : 'or paste the QR link'} dir="ltr" /></label>
          <label className="field"><span>{ar ? 'رمز التحقق' : 'Verification code'}</span><input value={cert.code} onChange={(e) => setCert({ ...cert, code: e.target.value })} maxLength={10} dir="ltr" /></label>
        </div>
        <RuleList rules={certRules} lang={lang} />
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button className="btn primary" disabled={verifying || !certRules.every((r) => r.ok)} onClick={() => verify()}><Search size={15} /> {verifying ? '…' : (ar ? 'تحقق' : 'Verify')}</button>
          {certRules.every((r) => r.ok) && <button className="btn ghost" onClick={shareLink} title={ar ? 'رابط يفتح هذه الصفحة ويتحقق مباشرة' : 'A link that opens this page and verifies at once'}>
            {copied ? <Check size={14} /> : <Link2 size={14} />} {copied ? (ar ? 'نُسخ' : 'Copied') : (ar ? 'نسخ الرابط' : 'Copy link')}</button>}
          {staffCanIssue && <button className="btn ghost" disabled={issuing} onClick={issueSample} title={ar ? 'موظف: أصدر شهادة تجريبية حقيقية للتجربة' : 'Staff: issue a real sample certificate to try'}>
            <Stamp size={14} /> {issuing ? '…' : (ar ? 'إصدار شهادة تجريبية' : 'Issue a sample certificate')}</button>}
          {ver && 'validity' in ver && <button className="btn ghost" onClick={() => { const c = { ...cert, code: cert.code === 'WRONGCODE1' ? 'WRONGCODE2' : 'WRONGCODE1' }; setCert(c); void verify(c) }}>
            {ar ? 'جرّب رمزًا خاطئًا' : 'Try a wrong code'}</button>}
        </div>
        {issueErr && <p role="alert" style={{ color: 'var(--red)', fontSize: 13 }}>{issueErr}</p>}
        {ver && ('validity' in ver ? (
          <div className={`result ${ver.validity === 'VALID' ? 'ok' : ver.validity === 'EXPIRED' ? 'warn' : 'bad'}`} role="status">
            <div className="big">{ver.validity === 'VALID' ? <ShieldCheck color="var(--green)" /> : <ShieldAlert color={ver.validity === 'EXPIRED' ? 'var(--amber)' : 'var(--red)'} />}
              {ver.validity === 'VALID' ? (ar ? 'شهادة صحيحة' : 'Genuine and valid') : ver.validity === 'EXPIRED' ? (ar ? 'منتهية الصلاحية' : 'Expired') : (ar ? 'ملغاة' : 'Revoked')}
              <span className="pill" style={{ marginInlineStart: 'auto' }}>{ver.validity}</span></div>
            <dl className="kv">
              <dt>{ar ? 'الشهادة' : 'Certificate'}</dt><dd>{ar ? ver.titleNl : ver.titleEn}</dd>
              <dt>{ar ? 'الرقم' : 'Number'}</dt><dd className="mono">{ver.number}</dd>
              <dt>{ar ? 'الحامل' : 'Holder'}</dt><dd>{ver.holder} <span className="muted" style={{ fontSize: 12 }}>{ar ? '(مختصر لحماية الخصوصية)' : '(initials only, for privacy)'}</span></dd>
              <dt>{ar ? 'الإصدار' : 'Issued'}</dt><dd>{ver.issuedOn}{ver.validUntil ? ` → ${ver.validUntil}` : ''}</dd>
            </dl>
          </div>
        ) : (
          <div className="result bad" role="alert">
            <div className="big"><ShieldX color="var(--red)" /> {ver.status === 404 ? (ar ? 'لا تطابق أي شهادة' : 'No matching certificate') : (ar ? 'تعذّر التحقق' : 'Could not verify')}
              {ver.status > 0 && <span className="pill" style={{ marginInlineStart: 'auto' }}>HTTP {ver.status}</span>}</div>
            <div className="muted" style={{ fontSize: 13 }}>{ver.status === 429 ? (ar ? 'محاولات كثيرة من هذا العنوان؛ انتظر قليلًا (حماية من التخمين).' : 'Too many tries from this address; wait a moment (guessing protection).') : ver.text}</div>
          </div>
        ))}
      </Card>
    </div>
  )
}
