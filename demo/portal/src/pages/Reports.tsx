import { useEffect, useState } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Download, FileBarChart, Play, ListChecks } from 'lucide-react'
import { ApiError, call, money } from '../api'
import { autoSignInStaff, fresh, load } from '../auth'
import { Card, Skeleton } from '../components'
import { useI18n } from '../i18n'

// Reports (demo D4.7; RPT-001, RPT-002, RPT-004): run any published report for a period, by day or month, see the
// table and chart, and download the same run as CSV (bilingual title, print date). Read as dev.checker (report.read).

type Def = { code: string; titleEn: string; titleNl: string; amountField?: string | null }
type Row = { period: string; region?: string | null; eventType: string; count: number; amountMinor?: number | null }
type Run = { code: string; titleEn: string; titleNl: string; from: string; to: string; period: string; rows: Row[]; printedAt: string }

const day = (d: Date) => d.toISOString().slice(0, 10)

export default function Reports({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [ready, setReady] = useState(() => !!load('checker'))
  const [defs, setDefs] = useState<Def[] | null>(null)
  const [form, setForm] = useState({ code: 'VIOLATIONS_RECORDED', from: day(new Date(Date.now() - 29 * 86_400_000)), to: day(new Date()), period: 'DAY' })
  const [run, setRun] = useState<Run | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const pw = import.meta.env.VITE_ITMS_CHECKER_PASSWORD as string | undefined
    if (!ready && pw) autoSignInStaff('dev.checker', pw, 'checker').then((s) => { if (s) setReady(true) }).catch(() => {})
  }, [ready])
  useEffect(() => {
    if (ready) call<{ items: Def[] }>('checker', 'GET', '/v1/reports', undefined, lang).then((r) => setDefs(r.items)).catch(() => setDefs([]))
  }, [ready, lang])

  const body = () => ({ code: form.code, from: form.from, to: form.to, period: form.period })
  const execute = async () => {
    setBusy(true)
    try { setRun(await call<Run>('checker', 'POST', '/v1/report-runs', body(), lang)) } catch (e) { notify(e instanceof ApiError ? e.message : String(e), true) } finally { setBusy(false) }
  }
  const csv = async () => {
    const s = await fresh('checker')
    if (!s) return
    const res = await fetch('/v1/report-runs', { method: 'POST', body: JSON.stringify(body()), headers: {
      Authorization: `Bearer ${s.accessToken}`, 'Content-Type': 'application/json', Accept: 'text/csv', 'X-Channel': 'BACKOFFICE',
      'Idempotency-Key': crypto.randomUUID(), 'Accept-Language': lang } })
    if (!res.ok) { notify(`CSV ${res.status}`, true); return }
    const url = URL.createObjectURL(await res.blob())
    const a = document.createElement('a')
    a.href = url; a.download = `${form.code}_${form.from}_${form.to}.csv`; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 2000)
    notify(ar ? 'تم تنزيل ملف CSV' : 'CSV downloaded')
  }

  const def = defs?.find((d) => d.code === form.code)
  const withAmount = run?.rows.some((r) => r.amountMinor != null)
  const chart = (run?.rows ?? []).reduce<Record<string, { period: string; count: number }>>((m, r) => {
    m[r.period] = { period: r.period, count: (m[r.period]?.count ?? 0) + r.count }; return m
  }, {})

  if (!ready) return <div className="card empty">{ar ? 'جارٍ الدخول…' : 'Signing in…'}</div>
  return (
    <div className="grid g-3">
      <Card title={ar ? 'تشغيل تقرير' : 'Run a report'} icon={<FileBarChart size={16} />} right={<span className="pill">RPT-001</span>}>
        {defs === null ? <Skeleton /> : (
          <>
            <label className="field"><span>{ar ? 'التقرير' : 'Report'}</span>
              <select value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })}>
                {defs.map((d) => <option key={d.code} value={d.code}>{ar ? d.titleNl : d.titleEn}</option>)}</select></label>
            <div className="grid g-2" style={{ gap: 10 }}>
              <label className="field"><span>{ar ? 'من' : 'From'}</span><input type="date" value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })} /></label>
              <label className="field"><span>{ar ? 'إلى' : 'To'}</span><input type="date" value={form.to} onChange={(e) => setForm({ ...form, to: e.target.value })} /></label>
            </div>
            <label className="field"><span>{ar ? 'التجميع' : 'Group by'}</span>
              <select value={form.period} onChange={(e) => setForm({ ...form, period: e.target.value })}><option value="DAY">{ar ? 'يوم' : 'Day'}</option><option value="MONTH">{ar ? 'شهر' : 'Month'}</option></select></label>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button className="btn primary" disabled={busy} onClick={execute}><Play size={15} /> {ar ? 'تشغيل' : 'Run'}</button>
              <button className="btn" onClick={csv}><Download size={15} /> CSV</button>
            </div>
            <p className="muted" style={{ fontSize: 12.5 }}>{ar ? 'تُحسب التقارير من مستودع الأحداث وتُقيَّد بنطاق بيانات المستخدم؛ كل تشغيل مسجل.' : 'Reports are computed from the event warehouse and limited to the caller’s data scope; every run is audited.'}</p>
          </>
        )}
      </Card>
      <Card className="span-2" title={run ? (ar ? run.titleNl : run.titleEn) : (def ? (ar ? def.titleNl : def.titleEn) : '')} icon={<FileBarChart size={16} />}
        right={run && <span className="pill">{run.from} → {run.to} · {run.printedAt}</span>}>
        {!run ? <div className="empty">{ar ? 'اختر تقريرًا واضغط تشغيل' : 'Choose a report and press Run'}</div> : run.rows.length === 0 ? <div className="empty">{ar ? 'لا بيانات في هذه الفترة' : 'No events in this period'}</div> : (
          <>
            <div style={{ height: 200, direction: 'ltr' }}>
              <ResponsiveContainer>
                <BarChart data={Object.values(chart)} margin={{ left: -18, right: 8, top: 8 }}>
                  <CartesianGrid stroke="rgba(148,163,214,0.1)" vertical={false} />
                  <XAxis dataKey="period" stroke="#5d6888" fontSize={11} tickLine={false} />
                  <YAxis stroke="#5d6888" fontSize={11} tickLine={false} axisLine={false} allowDecimals={false} />
                  <Tooltip contentStyle={{ background: '#111a2e', border: '1px solid rgba(148,163,214,0.28)', borderRadius: 12, color: '#e8edf9' }} />
                  <Bar dataKey="count" fill="#22d3ee" radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
            <div className="table-wrap"><table className="tbl">
              <thead><tr><th>{ar ? 'الفترة' : 'Period'}</th><th>{ar ? 'المنطقة' : 'Region'}</th><th>{ar ? 'الحدث' : 'Event'}</th><th className="num">{ar ? 'العدد' : 'Count'}</th>{withAmount && <th className="num">{ar ? 'المبلغ' : 'Amount'}</th>}</tr></thead>
              <tbody>{run.rows.map((r, i) => (
                <tr key={i}><td>{r.period}</td><td>{r.region ?? '—'}</td><td className="mono">{r.eventType}</td><td className="num">{r.count}</td>{withAmount && <td className="num">{r.amountMinor != null ? money(r.amountMinor, 'XTS', lang) : '—'}</td>}</tr>))}</tbody>
            </table></div>
          </>
        )}
      </Card>
      <Card className="req-progress page-enter" style={{ gridColumn: '1 / -1' }}>
      <div className="req-progress-head">
        <ListChecks size={16} />
        <span>{ar ? 'متطلبات التقارير' : 'Reports requirements'}</span>
        <span className="pill ok">{ar ? 'مباشر' : 'live'}</span>
      </div>
      <div className="req-progress-items">
        {[
          { id: 'RPT-001', en: 'Violations recorded per period (day/month)', ar: 'مخالفات مسجلة لكل فترة (يوم/شهر)' },
          { id: 'RPT-002', en: 'Payments confirmed with amounts per period', ar: 'مدفوعات مؤكدة بالمبالغ لكل فترة' },
          { id: 'RPT-004', en: 'CSV download (bilingual title, print date)', ar: 'تنزيل CSV (عنوان ثنائي اللغة، تاريخ الطباعة)' },
          { id: 'RPT-010', en: 'Operational reports by service, region, period', ar: 'تقارير تشغيلية حسب الخدمة، المنطقة، الفترة' },
          { id: 'RPT-011', en: 'Export PDF/Excel/CSV', ar: 'تصدير PDF/Excel/CSV' },
          { id: 'RPT-012', en: 'Scheduled report runs', ar: 'تشغيل تقارير مجدول' },
          { id: 'RPT-021', en: 'Role-scoped access to reports', ar: 'وصول مقيد بالصلاحيات للتقارير' },
          { id: 'RPT-022', en: 'Classification-driven masking in reports', ar: 'إخفاء يعتمد على التصنيف في التقارير' },
          { id: 'CC-121', en: 'Entity history timeline in report context', ar: 'خط زمني للكيان ضمن سياق التقرير' },
          { id: 'SEC-014', en: 'Masking by classification; reveal audited', ar: 'إخفاء حسب التصنيف؛ الكشف مدقق' },
        ].map((r) => (
          <div key={r.id} className="req-progress-row">
            <span className="req-progress-id mono" style={{ color: 'var(--uae-green)' }}>{r.id}</span>
            <span className="req-progress-text" style={{ flex: 1 }}>{ar ? r.ar : r.en}</span>
            <span className="pill ok">{ar ? 'مبني' : 'built'}</span>
          </div>
        ))}
      </div>
    </Card>
    </div>
  )
}
