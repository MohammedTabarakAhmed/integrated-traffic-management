import { useCallback, useEffect, useRef, useState } from 'react'
import { Ban, Camera, Lock, Radar, ShieldAlert, Siren, Smartphone, TrafficCone, Zap } from 'lucide-react'
import { ApiError, call, when } from '../api'
import { load, signInStaff } from '../auth'
import { Card, Gauge, StatusPill } from '../components'
import { useI18n } from '../i18n'

const DEMO_NATIONAL_ID = '784199012345671'

type Account = {
  fileNumber: string; activePoints: number; threshold: number
  activeSuspension?: { id: string; from: string; until: string; ordinal: number; holdId?: string | null } | null
  entries: { id: string; points: number; occurredOn: string; status: string }[]
  suspensions: { id: string; ordinal: number; from: string; until: string; pointsAtOrder: number; holdId?: string | null }[]
}
type Hold = { id: string; holdType?: string; status?: string; module?: string; reason?: string; blockedServices?: string[] }
type FeedItem = { at: Date; text: string; tone: 'info' | 'warn' | 'bad' | 'ok' }

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
      <p className="muted" style={{ marginTop: 0 }}>{lang === 'ar' ? 'حساب تطوير محلي' : 'Local development account (SSO + MFA in production)'}</p>
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

export default function ControlRoom() {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [staff, setStaff] = useState(() => load('staff'))
  const [file, setFile] = useState<string | null>(null)
  const [acct, setAcct] = useState<Account | null>(null)
  const [hold, setHold] = useState<Hold | null>(null)
  const [feed, setFeed] = useState<FeedItem[]>([])
  const [busy, setBusy] = useState<string | null>(null)
  const lastSusp = useRef<string | null>(null)

  const log = useCallback((text: string, tone: FeedItem['tone'] = 'info') =>
    setFeed((f) => [{ at: new Date(), text, tone }, ...f].slice(0, 40)), [])

  // Find the demo customer's traffic file once.
  useEffect(() => {
    if (!staff || file) return
    call<{ items: { fileNumber: string }[] }>('staff', 'POST', '/v1/traffic-files/searches',
      { document: { type: 'NATIONAL_ID', nationalIdNo: DEMO_NATIONAL_ID } })
      .then((r) => { setFile(r.items[0]?.fileNumber ?? null); log(`Traffic file ${r.items[0]?.fileNumber} linked to national ID …${DEMO_NATIONAL_ID.slice(-4)}`) })
      .catch((e) => log(`Search failed: ${e.message}`, 'bad'))
  }, [staff, file, log])

  // Poll the black-point account: points land when the objection window lapses (one minute locally).
  useEffect(() => {
    if (!staff || !file) return
    const tick = () => call<Account>('staff', 'GET', `/v1/black-point-accounts/${file}`)
      .then((a) => {
        setAcct(a)
        const s = a.activeSuspension
        if (s && lastSusp.current !== s.id) {
          lastSusp.current = s.id
          log(`SUSPENSION #${s.ordinal} ordered at ${a.activePoints} points: ${s.from} → ${s.until}`, 'bad')
          if (s.holdId) call<Hold>('staff', 'GET', `/v1/holds/${s.holdId}`).then((h) => { setHold(h); log(`Hold ${h.id.slice(0, 8)} placed on the traffic file (services blocked)`, 'warn') }).catch(() => {})
        }
      })
      .catch((e) => { if (e instanceof ApiError && e.status === 401) setStaff(null) })
    tick()
    const id = setInterval(tick, 3000)
    return () => clearInterval(id)
  }, [staff, file, log])

  const fire = async (s: (typeof shots)[number]) => {
    if (!file) return
    setBusy(s.code)
    try {
      const v = await call<{ number: string }>('staff', 'POST', '/v1/violations', {
        catalogueCode: s.code, occurredAt: new Date(Date.now() - 60_000).toISOString().replace(/\.\d+Z$/, 'Z'),
        region: 'DXB', location: s.loc, plate: 'A 12345', ownerFileNumber: file, driverFileNumber: file, source: 'OFFICER',
      })
      log(`${s.en}: violation ${v.number} recorded (+${s.pts} points after the objection window)`, 'warn')
    } catch (e) {
      log(`Rejected: ${(e as Error).message}`, 'bad')
    } finally {
      setBusy(null)
    }
  }

  if (!staff) return <StaffSignIn onDone={() => setStaff(load('staff'))} />

  const pending = feed.filter((f) => f.text.includes('recorded')).length
  const susp = acct?.activeSuspension
  return (
    <>
      {susp && (
        <div className="banner" role="alert">
          <Siren color="var(--red)" />
          <div><b>UAT-1 · {ar ? 'إيقاف الرخصة' : 'Licence suspended'} #{susp.ordinal}</b>
            <span className="muted"> · {susp.from} → {susp.until}{hold ? ` · hold ${hold.id.slice(0, 8)}` : ''}</span></div>
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
        <Card title={ar ? 'النقاط السوداء' : 'Black points'} icon={<ShieldAlert size={16} />}>
          {acct ? <Gauge value={acct.activePoints} max={acct.threshold} label={`${ar ? 'من' : 'of'} ${acct.threshold}`} /> : <div className="empty">…</div>}
          <div className="muted" style={{ textAlign: 'center', fontSize: 13 }}>
            {pending > 0 && !susp ? (ar ? 'نقاط بانتظار انتهاء مهلة الاعتراض' : 'Points land when the objection window lapses (1 min locally)') : (ar ? 'عند 24 نقطة: إيقاف + حجز' : 'At 24 points: suspension + hold')}
          </div>
        </Card>
        <Card title={ar ? 'الإيقافات والحجوزات' : 'Suspensions & holds'} icon={<Ban size={16} />}>
          {acct && acct.suspensions.length > 0 ? (
            <div className="list">
              {acct.suspensions.map((s) => (
                <div className="row" key={s.id}>
                  <div className="ico red"><Lock size={16} /></div>
                  <div className="grow"><div className="title">#{s.ordinal} · {s.pointsAtOrder} pts</div><div className="meta">{s.from} → {s.until}</div></div>
                  {s.holdId && <span className="pill bad">HOLD</span>}
                </div>
              ))}
              {hold && <div className="row"><div className="ico"><Lock size={16} /></div><div className="grow"><div className="title mono">{hold.id}</div><div className="meta">{hold.holdType} · {hold.module}</div></div>{hold.status && <StatusPill s={hold.status} />}</div>}
            </div>
          ) : <div className="empty">{ar ? 'لا توجد إيقافات' : 'No suspension yet'}</div>}
        </Card>
        <Card className="span-2" title={ar ? 'سجل الأحداث' : 'Event feed'} icon={<Camera size={16} />} right={<span className="pill">{feed.length}</span>}>
          <div className="feed">
            {feed.length === 0 ? <div className="empty">{ar ? 'بانتظار الأحداث' : 'Waiting for events'}</div> :
              feed.map((f, i) => (
                <div key={i} className={`feed-row ${f.tone}`}><span className="mono muted">{f.at.toLocaleTimeString('en-GB')}</span><span>{f.text}</span></div>
              ))}
          </div>
        </Card>
        <Card title={ar ? 'نقاط فعّالة' : 'Point entries'} icon={<Zap size={16} />}>
          {acct && acct.entries.length > 0 ? (
            <div className="list">
              {acct.entries.slice(0, 6).map((e) => (
                <div className="row" key={e.id}><div className="grow"><div className="title">+{e.points}</div><div className="meta">{when(e.occurredOn, lang)}</div></div><StatusPill s={e.status} /></div>
              ))}
            </div>
          ) : <div className="empty">—</div>}
        </Card>
      </div>
    </>
  )
}
