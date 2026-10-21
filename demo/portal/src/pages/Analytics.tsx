import { useCallback, useEffect, useRef, useState } from 'react'
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Activity, Car, Fingerprint, Link2, Siren, Wallet, Camera, ListChecks } from 'lucide-react'
import { call, money } from '../api'
import { load, signInStaff } from '../auth'
import { Card, CountUp, Kpi } from '../components'
import { useI18n } from '../i18n'

// Analytics (Increment 11): reporting-service runs over the event warehouse (RPT-001, RPT-002) and the central audit
// hash chain (CC-120, CC-122). Read as dev.checker, who holds report.read and audit.read; dev.maker does not.

type Row = { period: string; region?: string; eventType: string; count: number; amountMinor?: number | null }
type Run = { code: string; titleEn: string; titleNl: string; rows: Row[] }
type Audit = { seq: number; occurredAt: string; service: string; action: string; entityType: string; entityId: string; actorUsername?: string; channel: string; hash: string }

const REPORTS = ['VIOLATIONS_RECORDED', 'PAYMENTS_CONFIRMED', 'VEHICLE_REGISTRATIONS', 'ACCIDENTS_REPORTED'] as const
const FEED = ['violation-service', 'payment-service', 'vehicle-service', 'notification-service', 'traffic-file-service', 'accident-service', 'hold-eligibility-service', 'permit-service']
const day = (d: Date) => d.toISOString().slice(0, 10)

export default function Analytics() {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [checker, setChecker] = useState(() => load('checker'))
  const [runs, setRuns] = useState<Record<string, Run>>({})
  const [feed, setFeed] = useState<Audit[]>([])
  const [chain, setChain] = useState<{ checked: number; intact: boolean; lastSeq?: number; brokenAtSeq?: number | null } | null>(null)
  const [verifying, setVerifying] = useState(false)
  const since = useRef(new Date(Date.now() - 20 * 60_000).toISOString())

  useEffect(() => {
    const pw = import.meta.env.VITE_ITMS_CHECKER_PASSWORD as string | undefined
    if (!checker && pw) signInStaff('dev.checker', pw, 'checker').then(setChecker).catch(() => {})
  }, [checker])

  const loadReports = useCallback(async () => {
    const to = new Date(); const from = new Date(Date.now() - 6 * 86_400_000)
    const out: Record<string, Run> = {}
    await Promise.all(REPORTS.map(async (code) => {
      out[code] = await call<Run>('checker', 'POST', '/v1/report-runs', { code, from: day(from), to: day(to), period: 'DAY' }, lang)
    }))
    setRuns(out)
  }, [lang])

  const loadFeed = useCallback(async () => {
    const pages = await Promise.all(FEED.map((s) =>
      call<{ items: Audit[] }>('checker', 'GET', `/v1/audit-records?service=${s}&from=${encodeURIComponent(since.current)}&limit=200`).then((p) => p.items).catch(() => [] as Audit[])))
    setFeed(pages.flat().sort((a, b) => b.seq - a.seq).slice(0, 30))
  }, [])

  const verify = useCallback(async () => {
    setVerifying(true)
    try { setChain(await call('checker', 'GET', '/v1/audit-integrity')) } catch { /* keep the last result; the button is enabled again */ } finally { setVerifying(false) }
  }, [])

  useEffect(() => {
    if (!checker) return
    void loadReports(); void loadFeed(); void verify()
    const a = setInterval(loadFeed, 4000)
    const b = setInterval(loadReports, 15000)
    return () => { clearInterval(a); clearInterval(b) }
  }, [checker, loadReports, loadFeed, verify])

  // 7 days on the x axis, even when a day has no events.
  const days = Array.from({ length: 7 }, (_, i) => day(new Date(Date.now() - (6 - i) * 86_400_000)))
  const sum = (code: string, d: string, f: 'count' | 'amountMinor') =>
    (runs[code]?.rows ?? []).filter((r) => r.period === d).reduce((s, r) => s + (r[f] ?? 0), 0)
  const series = days.map((d) => ({
    day: d.slice(5),
    violations: sum('VIOLATIONS_RECORDED', d, 'count'),
    payments: sum('PAYMENTS_CONFIRMED', d, 'count'),
    fines: sum('VIOLATIONS_RECORDED', d, 'amountMinor') / 100,
    collected: sum('PAYMENTS_CONFIRMED', d, 'amountMinor') / 100,
  }))
  const total = (code: string, f: 'count' | 'amountMinor') => (runs[code]?.rows ?? []).reduce((s, r) => s + (r[f] ?? 0), 0)
  const tip = { contentStyle: { background: '#111a2e', border: '1px solid rgba(148,163,214,0.28)', borderRadius: 12, color: '#e8edf9' } }

  if (!checker) return <div className="card empty">{ar ? 'جارٍ دخول المدقق…' : 'Signing in the auditor (dev.checker)…'}</div>

  return (
    <>
      <div className="grid g-4" style={{ marginBottom: 18 }}>
        <Kpi tone="amber" icon={<Camera size={18} />} value={<CountUp to={total('VIOLATIONS_RECORDED', 'count')} />} label={ar ? 'مخالفات مسجلة (7 أيام)' : 'Violations recorded (7 days)'} />
        <Kpi tone="green" icon={<Wallet size={18} />} value={money(total('PAYMENTS_CONFIRMED', 'amountMinor'), 'XTS', lang)} label={ar ? 'إيرادات محصلة' : 'Revenue collected'} />
        <Kpi icon={<Car size={18} />} value={<CountUp to={total('VEHICLE_REGISTRATIONS', 'count')} />} label={ar ? 'تسجيلات المركبات' : 'Vehicle registrations'} />
        <Kpi tone="violet" icon={<Siren size={18} />} value={<CountUp to={total('ACCIDENTS_REPORTED', 'count')} />} label={ar ? 'حوادث مبلغ عنها' : 'Accidents reported'} />
      </div>
      <div className="grid g-3">
        <Card className="span-2" title={ar ? 'المخالفات والمدفوعات يوميًا' : 'Violations and payments per day'} icon={<Activity size={16} />} right={<span className="pill">RPT-001 · RPT-002</span>}>
          <div style={{ height: 260, direction: 'ltr' }}>
            <ResponsiveContainer>
              <AreaChart data={series} margin={{ left: -18, right: 8, top: 8 }}>
                <defs>
                  <linearGradient id="gv" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#f43f5e" stopOpacity={0.5} /><stop offset="100%" stopColor="#f43f5e" stopOpacity={0} /></linearGradient>
                  <linearGradient id="gp" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#22d3ee" stopOpacity={0.5} /><stop offset="100%" stopColor="#22d3ee" stopOpacity={0} /></linearGradient>
                </defs>
                <CartesianGrid stroke="rgba(148,163,214,0.1)" vertical={false} />
                <XAxis dataKey="day" stroke="#5d6888" fontSize={12} tickLine={false} />
                <YAxis stroke="#5d6888" fontSize={12} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip {...tip} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Area type="monotone" dataKey="violations" name={ar ? 'مخالفات' : 'Violations'} stroke="#f43f5e" strokeWidth={2} fill="url(#gv)" />
                <Area type="monotone" dataKey="payments" name={ar ? 'مدفوعات' : 'Payments'} stroke="#22d3ee" strokeWidth={2} fill="url(#gp)" />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </Card>
        <Card title={ar ? 'سلسلة التدقيق' : 'Audit hash chain'} icon={<Link2 size={16} />} right={<span className="pill">CC-122</span>}>
          <div className={`chain ${chain?.intact === false ? 'broken' : ''}`}>
            <Fingerprint size={44} className={verifying ? 'pulse' : ''} />
            <b>{verifying && !chain ? (ar ? 'جارٍ إعادة حساب السلسلة…' : 'Re-hashing the chain…') : chain ? (chain.intact ? (ar ? 'السلسلة سليمة' : 'Chain intact') : (ar ? `انكسار عند ${chain.brokenAtSeq}` : `Broken at ${chain.brokenAtSeq}`)) : '…'}</b>
            <span className="muted">{chain ? `${chain.checked.toLocaleString()} ${ar ? 'سجل تم التحقق منه' : 'records re-hashed'}` : ''}</span>
            <button className="btn" onClick={verify} disabled={verifying}>{verifying ? (ar ? 'جارٍ التحقق…' : 'Verifying…') : ar ? 'تحقق الآن' : 'Verify now'}</button>
          </div>
          <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{ar ? 'كل تغيير في كل خدمة يُسجَّل مرة واحدة في سلسلة تجزئة مركزية لا تقبل التعديل.' : 'Every change in every service lands once in one central, append-only hash chain.'}</p>
        </Card>
        <Card className="span-2" title={ar ? 'الغرامات الصادرة مقابل المحصّل' : 'Fines issued vs collected'} icon={<Wallet size={16} />}>
          <div style={{ height: 230, direction: 'ltr' }}>
            <ResponsiveContainer>
              <BarChart data={series} margin={{ left: -6, right: 8, top: 8 }}>
                <CartesianGrid stroke="rgba(148,163,214,0.1)" vertical={false} />
                <XAxis dataKey="day" stroke="#5d6888" fontSize={12} tickLine={false} />
                <YAxis stroke="#5d6888" fontSize={12} tickLine={false} axisLine={false} />
                <Tooltip {...tip} formatter={(v: number) => `AED ${v.toLocaleString()}`} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="fines" name={ar ? 'غرامات صادرة' : 'Fines issued'} fill="#8b5cf6" radius={[6, 6, 0, 0]} />
                <Bar dataKey="collected" name={ar ? 'محصّل' : 'Collected'} fill="#34d399" radius={[6, 6, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>
        <Card title={ar ? 'سجل التدقيق المباشر' : 'Live audit trail'} icon={<Fingerprint size={16} />} right={<span className="pill info"><span className="dot live" /> CC-120</span>}>
          <div className="feed" style={{ maxHeight: 250 }}>
            {feed.length === 0 ? <div className="empty">{ar ? 'بانتظار الأحداث' : 'Waiting for events'}</div> : feed.map((a) => (
              <div key={a.seq} className="audit-row">
                <div><span className="mono muted">#{a.seq}</span> <b>{a.action}</b></div>
                <div className="meta"><span>{a.service.replace('-service', '')}</span><span>{a.actorUsername ?? '—'}</span><span>{a.channel}</span><span className="mono">{a.hash.slice(0, 10)}…</span></div>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <section className="card req-progress page-enter" style={{ gridColumn: '1 / -1' }}>
        <div className="req-progress-head">
          <ListChecks size={16} />
          <span>{ar ? 'متطلبات التحليلات والتدقيق' : 'Analytics & audit requirements'}</span>
          <span className="pill ok">{ar ? 'مباشر' : 'live'}</span>
        </div>
        <div className="req-progress-items">
          {[
            { id: 'RPT-001', en: 'Violations recorded per day/week/month (area chart)', ar: 'مخالفات مسجلة لكل يوم/أسبوع/شهر (رسم بياني مساحي)' },
            { id: 'RPT-002', en: 'Payments confirmed with amounts (line chart)', ar: 'مدفوعات مؤكدة بالمبالغ (رسم بياني خطي)' },
            { id: 'RPT-004', en: 'Reports downloadable as CSV (bilingual)', ar: 'التقارير قابلة للتحميل كـ CSV (ثنائي اللغة)' },
            { id: 'CC-120', en: 'Central audit trail: create/update/delete/view with user, role, time, channel, IP, old/new', ar: 'سجل تدقيق مركزي: إنشاء/تحديث/حذف/عرض مع المستخدم، الدور، الوقت، القناة، IP، القديم/الجديد' },
            { id: 'CC-122', en: 'Hash-chained audit records; Verify now re-hashes entire chain', ar: 'سجلات تدقيق متسلسلة بالتجزئة؛ تحقق الآن يعيد حساب السلسلة كاملة' },
            { id: 'NFR-040', en: 'OpenTelemetry tracing, Prometheus metrics, Tempo/Loki/Grafana', ar: 'تتبع OpenTelemetry، مقاييس Prometheus، Tempo/Loki/Grafana' },
            { id: 'CC-121', en: 'Entity history timeline (status changes with user/channel/reason)', ar: 'خط زمني للكيان (تغييرات الحالة مع المستخدم/القناة/السبب)' },
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
