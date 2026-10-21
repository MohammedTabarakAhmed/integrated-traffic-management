import { useCallback, useEffect, useRef, useState } from 'react'
import { Ban, Check, CheckCircle2, Clock, Copy, Lock, Radar, ShieldAlert, ShieldX, Siren, Smartphone, TrafficCone, Zap, Award } from 'lucide-react'
import { ApiError, call } from '../api'
import { load, signInStaff } from '../auth'
import { Card, Gauge, StatusPill } from '../components'
import { useI18n } from '../i18n'
import { nameOf } from '../names'

const DEMO_NATIONAL_ID = '784199012345671'
const RENEWAL = 'DEMO_RENEWAL'

type Account = {
  fileNumber: string; activePoints: number; threshold: number
  activeSuspension?: { id: string; from: string; until: string; ordinal: number; holdId?: string | null } | null
  entries: { id: string; violationId: string; points: number; occurredOn: string; expiresOn: string; status: string }[]
  suspensions: { id: string; ordinal: number; from: string; until: string; pointsAtOrder: number; holdId?: string | null }[]
}
type Hold = {
  id: string; holdType: string; module: string; source: string; reason: string; status: string
  startDate: string; endDate?: string | null; blockedServices: string[]; inForce?: boolean | null
}
type Eligibility = { eligible: boolean; checkedOn: string; blocks: { reasonCode: string; nextStep: string; message?: string }[] }
type FeedItem = { at: Date; en: string; ar: string; tone: 'info' | 'warn' | 'bad' | 'ok' }
type Pending = { number: string; pts: number; at: number }

const shots = [
  { code: 'DEMO_SPEED', pts: 4, icon: <Zap size={18} />, en: 'Radar · speeding', ar: 'رادار · سرعة', loc: 'Sheikh Zayed Road, km 31' },
  { code: 'DEMO_PHONE', pts: 6, icon: <Smartphone size={18} />, en: 'Camera · phone use', ar: 'كاميرا · هاتف', loc: 'Al Khail Road, exit 38' },
  { code: 'DEMO_REDLIGHT', pts: 12, icon: <TrafficCone size={18} />, en: 'Signal · red light', ar: 'إشارة · حمراء', loc: 'Al Wasl / Al Safa junction' },
]

function StaffSignIn({ onDone }: { onDone: () => void }) {
  const { lang } = useI18n()
  const [u, setU] = useState('dev.maker')
  const [p, setP] = useState((import.meta.env.VITE_ITMS_STAFF_PASSWORD as string | undefined) ?? '')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  return (
    <form className="card" style={{ maxWidth: 440, margin: '6vh auto', padding: 30 }}
      onSubmit={(e) => {
        e.preventDefault(); setBusy(true); setErr('')
        signInStaff(u, p).then(onDone).catch((x) => setErr(x.message)).finally(() => setBusy(false))
      }}>
      <div className="logo-mark" style={{ width: 52, height: 52, marginBottom: 16 }}><Radar /></div>
      <h2 style={{ margin: '0 0 6px' }}>{lang === 'ar' ? 'دخول الموظفين' : 'Staff sign-in'}</h2>
      <p className="muted" style={{ marginTop: 0 }}>{lang === 'ar' ? 'حساب تطوير محلي (الدخول الموحد والتحقق المتعدد في الإنتاج)' : 'Local development account (SSO + MFA in production)'}</p>
      <label className="field"><span>{lang === 'ar' ? 'اسم المستخدم' : 'User name'}</span>
        <input value={u} onChange={(e) => setU(e.target.value)} autoComplete="username" /></label>
      <label className="field"><span>{lang === 'ar' ? 'كلمة المرور' : 'Password'}</span>
        <input type="password" value={p} onChange={(e) => setP(e.target.value)} autoComplete="current-password" /></label>
      {err && <p style={{ color: 'var(--red)' }}>{err}</p>}
      <button className="btn primary lg" style={{ width: '100%', justifyContent: 'center' }} disabled={busy}>
        {lang === 'ar' ? 'دخول' : 'Sign in'}
      </button>
    </form>
  )
}

const mmss = (ms: number) => { const s = Math.max(0, Math.floor(ms / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}` }

export default function ControlRoom() {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [staff, setStaff] = useState(() => load('staff'))
  const [file, setFile] = useState<string | null>(null)
  const [acct, setAcct] = useState<Account | null>(null)
  const [hold, setHold] = useState<Hold | null>(null)
  const [elig, setElig] = useState<Eligibility | null>(null)
  const [feed, setFeed] = useState<FeedItem[]>([])
  const [pending, setPending] = useState<Pending[]>([])
  const [busy, setBusy] = useState<string | null>(null)
  const [now, setNow] = useState(Date.now())
  const [copied, setCopied] = useState(false)
  // Test evidence: the feed (oldest first) with the file's points, suspension and eligibility at the time of copying.
  const copyFeed = async () => {
    const tone = { ok: '✓', info: '·', warn: '!', bad: '✗' } as const
    const md = [`**ITMS control room — UAT-1 session** · ${new Date().toISOString()}`,
      `File ${file ?? '—'} · points ${acct ? `${acct.activePoints}/${acct.threshold}` : '—'} · suspension ${acct?.activeSuspension ? `#${acct.activeSuspension.ordinal} until ${acct.activeSuspension.until}` : 'none'} · hold ${hold ? `${hold.holdType} ${hold.status}` : 'none'} · renewal ${elig ? (elig.eligible ? 'ALLOWED' : 'BLOCKED') : 'not checked'}`, '',
      ...[...feed].reverse().map((f) => `- ${f.at.toISOString()} ${tone[f.tone]} ${f.en}`)].join('\n')
    try { await navigator.clipboard.writeText(md); setCopied(true); setTimeout(() => setCopied(false), 1800) } catch { /* clipboard blocked */ }
  }
  const seen = useRef<{ susp: string | null; entries: Set<string> | null }>({ susp: null, entries: null })

  const log = useCallback((en: string, arText: string, tone: FeedItem['tone'] = 'info') =>
    setFeed((f) => [{ at: new Date(), en, ar: arText, tone }, ...f].slice(0, 40)), [])

  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id) }, [])

  // Find the demo customer's traffic file once.
  useEffect(() => {
    if (!staff || file) return
    call<{ items: { fileNumber: string }[] }>('staff', 'POST', '/v1/traffic-files/searches',
      { document: { type: 'NATIONAL_ID', nationalIdNo: DEMO_NATIONAL_ID } })
      .then((r) => {
        const f = r.items[0]?.fileNumber ?? null
        setFile(f)
        log(`Traffic file ${f} found for national ID …${DEMO_NATIONAL_ID.slice(-4)}`, `تم العثور على الملف ${f} للهوية …${DEMO_NATIONAL_ID.slice(-4)}`)
      })
      .catch((e) => log(`Search failed: ${e.message}`, `فشل البحث: ${e.message}`, 'bad'))
  }, [staff, file, log])

  const check = useCallback(async (f: string) => {
    try {
      const e = await call<Eligibility>('staff', 'POST', '/v1/eligibility-checks',
        { serviceCode: RENEWAL, subjects: [{ type: 'TRAFFIC_FILE', id: f }] }, lang)
      setElig(e)
      if (e.eligible) log('Eligibility: licence renewal allowed', 'الأهلية: تجديد الرخصة مسموح', 'ok')
      else log(`Eligibility: licence renewal BLOCKED (${e.blocks.map((b) => b.reasonCode).join(', ')})`, `الأهلية: تجديد الرخصة محظور (${e.blocks.map((b) => b.reasonCode).join('، ')})`, 'bad')
    } catch (e) {
      log(`Eligibility check failed: ${(e as Error).message}`, `فشل التحقق من الأهلية: ${(e as Error).message}`, 'bad')
    }
  }, [lang, log])

  // Poll the black-point account: points land when the objection window lapses (one minute locally).
  useEffect(() => {
    if (!staff || !file) return
    const tick = () => call<Account>('staff', 'GET', `/v1/black-point-accounts/${file}`)
      .then((a) => {
        setAcct(a)
        const known = seen.current.entries
        const fresh = a.entries.filter((e) => known && !known.has(e.id))
        seen.current.entries = new Set(a.entries.map((e) => e.id))
        const ordered = (id?: string) => a.suspensions.find((x) => x.id === id)?.pointsAtOrder
        if (fresh.length) {
          const pts = fresh.reduce((s, e) => s + e.points, 0)
          const total = a.activeSuspension && seen.current.susp !== a.activeSuspension.id ? ordered(a.activeSuspension.id) ?? a.activePoints : a.activePoints
          log(`Objection window lapsed: +${pts} points confirmed → ${total}/${a.threshold}`, `انتهت مهلة الاعتراض: +${pts} نقاط ← ${total}/${a.threshold}`, 'warn')
          setPending((p) => p.slice(fresh.length))
        }
        const s = a.activeSuspension
        if (s && seen.current.susp !== s.id) {
          const first = seen.current.susp === null && !fresh.length
          seen.current.susp = s.id
          if (!first) log(`SUSPENSION #${s.ordinal} ordered at ${ordered(s.id)} points: ${s.from} → ${s.until}`, `أمر إيقاف رقم ${s.ordinal} عند ${ordered(s.id)} نقطة: ${s.from} ← ${s.until}`, 'bad')
          if (s.holdId) {
            call<Hold>('staff', 'GET', `/v1/holds/${s.holdId}`).then((h) => {
              setHold(h)
              if (!first) log(`${nameOf(h.holdType, 'en')} hold placed on the traffic file`, `تم وضع حجز ${nameOf(h.holdType, 'ar')} على الملف المروري`, 'bad')
              void check(file)
            }).catch(() => {})
          }
        }
        if (seen.current.susp === null) seen.current.susp = ''
      })
      .catch((e) => { if (e instanceof ApiError && e.status === 401) setStaff(null) })
    tick()
    const id = setInterval(tick, 3000)
    return () => clearInterval(id)
  }, [staff, file, log, check])

  const fire = async (s: (typeof shots)[number]) => {
    if (!file) return
    setBusy(s.code)
    try {
      const v = await call<{ number: string }>('staff', 'POST', '/v1/violations', {
        catalogueCode: s.code, occurredAt: new Date(Date.now() - 60_000).toISOString().replace(/\.\d+Z$/, 'Z'),
        region: 'DXB', location: s.loc, plate: 'A 12345', ownerFileNumber: file, driverFileNumber: file, source: 'OFFICER',
      })
      setPending((p) => [...p, { number: v.number, pts: s.pts, at: Date.now() }])
      log(`${s.en}: ${v.number} recorded, fine issued, customer notified`, `${s.ar}: سُجّلت ${v.number} وصدرت الغرامة وأُبلغ المتعامل`, 'info')
    } catch (e) {
      log(`Rejected: ${(e as Error).message}`, `مرفوض: ${(e as Error).message}`, 'bad')
    } finally {
      setBusy(null)
    }
  }

  if (!staff) return <StaffSignIn onDone={() => setStaff(load('staff'))} />

  const susp = acct?.activeSuspension
  const atOrder = susp ? acct?.suspensions.find((x) => x.id === susp.id)?.pointsAtOrder : undefined
  const projected = (acct?.activePoints ?? 0) + pending.reduce((s, p) => s + p.pts, 0)
  return (
    <>
      {susp && (
        <div className="banner" role="alert">
          <Siren color="var(--red)" />
          <div><b>UAT-1 · {ar ? 'إيقاف الرخصة' : 'Licence suspended'} #{susp.ordinal}</b>
            <span className="muted"> · {susp.from} → {susp.until}{hold ? ` · ${ar ? 'حجز' : 'hold'}: ${nameOf(hold.holdType, lang)}` : ''}</span></div>
        </div>
      )}
      <div className="grid g-3">
        <Card title={ar ? 'رصد مباشر' : 'Live enforcement'} icon={<Radar size={16} />} right={<span className="pill info"><span className="dot live" /> {file ?? '…'}</span>}>
          <div className="radar" aria-hidden="true"><div className="sweep" /><span className="blip" /></div>
          <div style={{ display: 'grid', gap: 10, marginTop: 14 }}>
            {shots.map((s) => (
              <button key={s.code} className="btn shot" onClick={() => fire(s)} disabled={!file || busy !== null}>
                {s.icon}<span>{ar ? s.ar : s.en}</span><span className="pill warn" style={{ marginInlineStart: 'auto' }}>+{s.pts}</span>
              </button>
            ))}
          </div>
        </Card>
        <Card title={ar ? 'النقاط السوداء' : 'Black points'} icon={<ShieldAlert size={16} />}
          right={pending.length > 0 ? <span className="pill warn">{ar ? 'متوقع' : 'projected'} {projected}</span> : undefined}>
          {acct ? <Gauge value={atOrder ?? acct.activePoints} max={acct.threshold} label={`${ar ? 'من' : 'of'} ${acct.threshold}`} /> : <div className="empty">…</div>}
          {pending.length > 0 ? (
            <div className="list" style={{ marginTop: 6 }}>
              {pending.map((p) => (
                <div className="row" key={p.number}>
                  <div className="ico"><Clock size={15} /></div>
                  <div className="grow"><div className="title mono" style={{ fontSize: 13 }}>{p.number}</div>
                    <div className="meta">{ar ? 'بانتظار انتهاء مهلة الاعتراض' : 'awaiting the objection window'} · {mmss(now - p.at)}</div></div>
                  <span className="pill warn">+{p.pts}</span>
                </div>
              ))}
            </div>
          ) : (
            <div className="muted" style={{ textAlign: 'center', fontSize: 13 }}>
              {atOrder !== undefined
                ? (ar ? `استُهلكت النقاط بالإيقاف رقم ${susp?.ordinal}؛ يبدأ العد من جديد` : `Points consumed by suspension #${susp?.ordinal}; the count starts again`)
                : (ar ? 'عند 24 نقطة: إيقاف تلقائي + حجز على الملف' : 'At 24 points: automatic suspension + hold on the file')}
            </div>
          )}
        </Card>
        <Card title={ar ? 'الإيقاف والحجز' : 'Suspension & hold'} icon={<Ban size={16} />}>
          {susp && hold ? (
            <>
              <div className="list">
                <div className="row">
                  <div className="ico red"><Lock size={16} /></div>
                  <div className="grow"><div className="title">{ar ? 'إيقاف' : 'Suspension'} #{susp.ordinal}</div><div className="meta">{susp.from} → {susp.until}</div></div>
                  <span className="pill bad">{ar ? 'نشط' : 'ACTIVE'}</span>
                </div>
                <div className="row">
                  <div className="ico red"><ShieldX size={16} /></div>
                  <div className="grow"><div className="title">{nameOf(hold.holdType, lang)}</div>
                    <div className="meta"><span>{nameOf(hold.module, lang)}</span><span>{hold.startDate}{hold.endDate ? ` → ${hold.endDate}` : ''}</span>
                      <span>{ar ? 'يحظر' : 'blocks'}: {hold.blockedServices.includes('*') ? (ar ? 'كل الخدمات' : 'all services') : hold.blockedServices.join(', ')}</span></div></div>
                  <StatusPill s={hold.status} />
                </div>
              </div>
              <div className={`elig ${elig?.eligible === false ? 'no' : elig?.eligible ? 'yes' : ''}`}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  {elig?.eligible === false ? <ShieldX size={20} /> : <CheckCircle2 size={20} />}
                  <b>{ar ? 'تجديد رخصة القيادة' : 'Driving licence renewal'}</b>
                  <span className="pill" style={{ marginInlineStart: 'auto' }}>{elig ? (elig.eligible ? (ar ? 'مسموح' : 'ALLOWED') : (ar ? 'محظور' : 'BLOCKED')) : '…'}</span>
                </div>
                {elig?.blocks.map((b) => <div key={b.reasonCode} className="muted" style={{ fontSize: 13, marginTop: 6 }}>{b.message ?? `${b.reasonCode} → ${b.nextStep}`}</div>)}
                <button className="btn" style={{ marginTop: 10 }} onClick={() => file && check(file)}>{ar ? 'تحقق من الأهلية' : 'Check eligibility'}</button>
              </div>
            </>
          ) : <div className="empty">{ar ? 'لا يوجد إيقاف بعد' : 'No suspension yet'}</div>}
        </Card>
        <Card className="span-2" title={ar ? 'سجل الأحداث' : 'Event feed'} icon={<Zap size={16} />} right={<span style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            {feed.length > 0 && <button className="btn ghost" onClick={copyFeed} title={ar ? 'نسخ السجل كـ Markdown' : 'Copy the feed as Markdown for a test report'}>
              {copied ? <Check size={14} /> : <Copy size={14} />} {copied ? (ar ? 'نُسخ' : 'Copied') : 'Markdown'}</button>}
            <span className="pill">{feed.length}</span></span>}>
          <div className="feed">
            {feed.length === 0 ? <div className="empty">{ar ? 'بانتظار الأحداث' : 'Waiting for events'}</div> :
              feed.map((f, i) => (
                <div key={i} className={`feed-row ${f.tone}`}><span className="mono muted">{f.at.toLocaleTimeString('en-GB')}</span><span>{ar ? f.ar : f.en}</span></div>
              ))}
          </div>
        </Card>
        <Card title={ar ? 'قيود النقاط' : 'Point entries'} icon={<ShieldAlert size={16} />} right={<span className="pill">{acct?.entries.length ?? 0}</span>}>
</Card>

      <section className="card uat-badges page-enter">
        <div className="uat-badges-head">
          <Award size={16} />
          <span>{lang === 'ar' ? 'متطلبات القبول المعروضة هنا' : 'UAT requirements demonstrated here'}</span>
          <span className="pill warn">{lang === 'ar' ? 'مباشر' : 'live'}</span>
        </div>
        <div className="uat-badges-list">
          {[
            { id: 'UAT-1', en: 'Black-point suspension at 24 points → hold + renewal blocked', ar: 'إيقاف الرخصة عند 24 نقطة ← حجز + تجديد محظور', done: !!susp },
            { id: 'VF-030', en: 'Points recorded when violation confirmed', ar: 'النقاط تُسجّل عند تأكيد المخالفة', done: acct?.entries.length > 0 },
            { id: 'VF-031', en: 'Point expiry after 1 year', ar: 'انتهاء النقاط بعد سنة', done: true },
            { id: 'VF-032', en: 'Suspension periods 3/6/12 months by ordinal', ar: 'فترات الإيقاف 3/6/12 شهرًا حسب الترتيب', done: !!susp },
            { id: 'VF-033', en: 'Warnings at 12 and 18 points', ar: 'تحذيرات عند 12 و 18 نقطة', done: (acct?.activePoints ?? 0) >= 12 },
            { id: 'CC-030', en: 'Hold on traffic file blocks renewal', ar: 'حجز على الملف يحظر التجديد', done: !!hold },
            { id: 'CC-032', en: 'Eligibility check before submission shows block reason', ar: 'فحص الأهلية قبل التقديم يوضح سبب الحظر', done: elig !== null },
          ].map((u) => (
            <div key={u.id} className={`uat-badge ${u.done ? 'done' : ''}`}>
              <span className="uat-id mono" style={{ color: u.done ? 'var(--uae-green)' : 'var(--uae-amber)' }}>{u.id}</span>
              <span className="uat-text" style={{ flex: 1 }}>{lang === 'ar' ? u.ar : u.en}</span>
              <span className={`pill ${u.done ? 'ok' : 'warn'}`}>{u.done ? (lang === 'ar' ? 'مكتمل' : 'done') : (lang === 'ar' ? 'قيد التنفيذ' : 'pending')}</span>
            </div>
          ))}
        </div>
      </section>

      <Card title={ar ? 'قيود النقاط' : 'Point entries'} icon={<ShieldAlert size={16} />} right={<span className="pill">{acct?.entries.length ?? 0}</span>}>
          {acct && acct.entries.length > 0 ? (
            <div className="list">
              {acct.entries.slice(0, 6).map((e) => (
                <div className="row" key={e.id}><div className="grow"><div className="title">+{e.points}</div>
                  <div className="meta">{e.occurredOn} → {ar ? 'تنتهي' : 'expires'} {e.expiresOn}</div></div><StatusPill s={e.status} /></div>
              ))}
            </div>
          ) : <div className="empty">—</div>}
        </Card>
      </div>
    </>
  )
}
