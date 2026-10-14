import { useState, type ReactNode } from 'react'
import { ArrowRight, Check, CheckCircle2, ClipboardCheck, Clock, Copy, Download, Database, KeyRound, Network, Play, Radio, Rocket, Server, ShieldCheck, Workflow, XCircle } from 'lucide-react'
import { load, signInStaff } from '../auth'
import { Card } from '../components'
import { useI18n } from '../i18n'

// QA & security (demo, for testers): live checks of the platform's controls with expected vs actual results, the
// UAT checklist with where to test each one, the request flow (system design) and what is still to come.

type Result = { status: number | string; pass: boolean; note: string; ms?: number; at?: string; corr?: string }
type Check = { id: string; en: string; ar: string; expect: string; ids: string; run: () => Promise<Result> }

const uuid = () => crypto.randomUUID()

// Every check calls the gateway through f(), which keeps the last correlation id so a result can be traced in the logs.
let lastCorr = ''
const f = async (input: string, init?: RequestInit) => {
  const r = await fetch(input, init)
  lastCorr = r.headers.get('x-correlation-id') ?? lastCorr
  return r
}

async function staffToken(): Promise<string> {
  let s = load('staff')
  const pw = import.meta.env.VITE_ITMS_STAFF_PASSWORD as string | undefined
  if (!s && pw) s = await signInStaff('dev.maker', pw, 'staff')
  if (!s) throw new Error('staff not signed in')
  return s.accessToken
}

const CHECKS: Check[] = [
  { id: 'no-token', en: 'Request without a token is refused at the gateway', ar: 'طلب بلا رمز يُرفض عند البوابة', expect: '401', ids: 'INT-001 · SEC-001',
    run: async () => { const r = await f('/v1/me/violations'); return { status: r.status, pass: r.status === 401, note: r.headers.get('www-authenticate') ?? '' } } },
  { id: 'tampered', en: 'A tampered token (bad signature) is refused', ar: 'رمز معدّل (توقيع غير صالح) يُرفض', expect: '401', ids: 'INT-001 · SEC-020',
    run: async () => { const t = await staffToken(); const bad = t.slice(0, -4) + (t.endsWith('AAAA') ? 'BBBB' : 'AAAA')
      const r = await f('/v1/violation-catalogue', { headers: { Authorization: `Bearer ${bad}` } }); return { status: r.status, pass: r.status === 401, note: 'signature checked against Keycloak keys' } } },
  { id: 'rbac', en: 'Officer without the report permission cannot read reports', ar: 'موظف بلا صلاحية التقارير لا يقرؤها', expect: '403', ids: 'CC-001',
    run: async () => { const r = await f('/v1/reports', { headers: { Authorization: `Bearer ${await staffToken()}`, 'X-Channel': 'BACKOFFICE' } })
      return { status: r.status, pass: r.status === 403, note: 'dev.maker lacks report.read (dev.checker has it)' } } },
  { id: 'own-data', en: 'Staff token cannot use the customer-only "my data" API', ar: 'رمز الموظف لا يصل إلى واجهة "بياناتي"', expect: '403', ids: 'CH-002',
    run: async () => { const r = await f('/v1/me/violations', { headers: { Authorization: `Bearer ${await staffToken()}`, 'X-Channel': 'BACKOFFICE' } })
      return { status: r.status, pass: r.status === 403, note: 'customer tokens only; the file comes from the token' } } },
  { id: 'idem', en: 'A changing call without Idempotency-Key is refused', ar: 'طلب تغيير بلا مفتاح عدم التكرار يُرفض', expect: '400', ids: 'platform rule (Idempotency-Key)',
    run: async () => { const r = await f('/v1/violations', { method: 'POST', headers: { Authorization: `Bearer ${await staffToken()}`, 'X-Channel': 'BACKOFFICE', 'Content-Type': 'application/json' }, body: '{}' })
      const p = await r.json().catch(() => ({})); return { status: r.status, pass: r.status === 400, note: p.code ?? '' } } },
  { id: 'validation', en: 'Invalid input answers RFC 9457 problem+json', ar: 'إدخال غير صالح يُعاد بصيغة problem+json', expect: '400 problem+json', ids: 'SEC-022',
    run: async () => { const r = await f('/v1/violations', { method: 'POST', headers: { Authorization: `Bearer ${await staffToken()}`, 'X-Channel': 'BACKOFFICE', 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }, body: '{"catalogueCode":"x"}' })
      const ct = r.headers.get('content-type') ?? ''; const p = await r.json().catch(() => ({}))
      return { status: r.status, pass: r.status === 400 && ct.includes('problem+json'), note: `${ct.split(';')[0]} · ${p.code ?? ''}` } } },
  { id: 'quota', en: 'Gateway rate limit quota on every protected call', ar: 'حد معدل الطلبات على كل طلب محمي', expect: 'limit 600/min', ids: 'INT-001',
    run: async () => { const r = await f('/v1/violation-catalogue', { headers: { Authorization: `Bearer ${await staffToken()}` } })
      const lim = r.headers.get('x-ratelimit-limit'); const rem = r.headers.get('x-ratelimit-remaining')
      return { status: r.status, pass: lim === '600', note: `X-RateLimit-Limit ${lim ?? '—'} · remaining ${rem ?? '—'}` } } },
  { id: 'burst', en: 'Burst of 35 calls on a public page hits the 30/min limit', ar: '35 طلبًا على صفحة عامة تتجاوز حد 30/دقيقة', expect: '429', ids: 'INT-001 · CC-083',
    run: async () => { const codes: number[] = []
      for (let i = 0; i < 35; i++) codes.push((await f(`/v1/certificate-verifications/DEMO-${i}`)).status)
      const n429 = codes.filter((c) => c === 429).length
      return { status: n429 ? 429 : codes[codes.length - 1], pass: n429 > 0, note: `${n429} of 35 refused with 429 (quota resets after 60 s)` } } },
  { id: 'correlation', en: 'Every answer carries a correlation id for tracing', ar: 'كل استجابة تحمل معرّف تتبع', expect: 'X-Correlation-Id', ids: 'INT-001',
    run: async () => { const r = await f('/v1/violation-catalogue', { headers: { Authorization: `Bearer ${await staffToken()}` } }); const c = r.headers.get('x-correlation-id')
      return { status: r.status, pass: !!c, note: c ?? 'missing' } } },
]

const UAT: [string, string, string, string, string, string][] = [
  ['UAT-1', 'Black points reach 24 → automatic suspension and hold', 'Control room: record fines until 24', 'Suspension #1 (3 months), LICENCE_SUSPENSION hold, renewal BLOCKED', 'control', 'smoke 37'],
  ['UAT-2', 'Fine on a rented car goes to the renter', 'Modules › Permits & rental › Radar fine on a rented car', 'Responsible = renter file, not the company', 'modules', 'smoke 41'],
  ['UAT-3', 'Insurance checked before any fee', 'Modules › Vehicles › Try with expired insurance', 'INSURANCE_EXPIRED, no payable created', 'modules', 'smoke 36'],
  ['UAT-4', 'Repeated gateway callback makes no second receipt', 'My dashboard › Pay all › receipt › Simulate retry', 'duplicate: true, same receipt number', 'dashboard', 'smoke 37'],
  ['UAT-5', 'Partner-state violation handled here', 'Modules › Cross-border › Receive a partner violation', 'Message received and mapped; exchange log shows it', 'modules', 'smoke 46'],
]

// Feature checks for testers: [feature, where to click, expected result, route, requirement ids]
const FEATURES: [string, string, string, string, string][] = [
  ['Transaction status timeline', 'My dashboard › click a transaction', 'Drawer with every status change, actor and channel', 'dashboard', 'CC-023'],
  ['Inbox read / unread', 'My dashboard › click an unread message', 'Message opens; the bell count drops by one', 'dashboard', 'CC-073'],
  ['Instalment plan', 'My services › tick the AED 6,000 fine › Create plan', 'Plan with a monthly schedule; under AED 5,000 is refused', 'services', 'VF-071'],
  ['Appointment', 'My services › pick a day and slot › Change › Cancel', 'Booked; a third change is refused; cancelled', 'services', 'CC-090 · CC-091'],
  ['Objection decision', 'Portal objection (within 1 min) › Back office › Objections', 'Accept or reject; a reduction above the approval limit is refused', 'backoffice', 'VF-050 · VF-051'],
  ['Case work queue', 'Back office › Work queue › Claim › Approve', 'Case completed; its history shows each step', 'backoffice', 'CC-060 · CC-063'],
  ['Lift a hold', 'Back office › Holds › Lift hold', 'Hold LIFTED; control room renewal check turns ALLOWED', 'backoffice', 'CC-030 · CC-032'],
  ['Record history', 'Modules › search a plate or violation › click the result', 'Audit timeline from the central hash chain', 'modules', 'CC-121'],
  ['Maker-checker', 'Administration › Maker-checker › Propose › Approve as maker', 'Maker refused; Approve as checker succeeds', 'admin', 'CC-020 · CC-114'],
  ['Fee quote', 'Administration › Fee calculator › Calculate', 'First registration: AED 400 with its fee line', 'admin', 'CC-040'],
  ['Report CSV', 'Reports › Run › CSV', 'Chart and table; a bilingual CSV file downloads', 'reports', 'RPT-001 · RPT-002'],
]

const FUTURE: [string, string, string][] = [
  ['12', 'Data migration from legacy systems: mapping, reconciliation, rollback (service starts in the stack, opt-in in the lite demo profile; quality gate and tests pending)', 'drafted'],
  ['13', 'AI agents behind the same APIs: model registry, review queue, drift and bias tests (service starts in the stack, opt-in in the lite profile; gate pending; rule-based assistant preview today)', 'preview'],
  ['14', 'Load test at 1.5 × peak, DR failover drill, DAST and pen test, WCAG 2.2 AA audit, Gate F acceptance', 'planned'],
  ['—', 'Per-client API quotas (consumer registry) on top of today’s per-address limits', 'planned'],
  ['—', 'Production: Kubernetes + Helm + Argo CD, TLS 1.3 end to end, HSM-held keys, SIEM', 'planned'],
  ['—', 'Full observability profile: Grafana dashboards, Tempo traces, Loki logs (the lite demo stack omits them)', 'full profile'],
  ['—', '172 BRD requirements not started yet (of 438); 140 in progress', 'roadmap'],
]

function Future({ children }: { children: ReactNode }) {
  return <span className="pill future"><Rocket size={12} /> {children}</span>
}

export default function Qa({ go }: { go: (r: string) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [res, setRes] = useState<Record<string, Result | 'run'>>({})

  const runOne = async (c: Check) => {
    setRes((r) => ({ ...r, [c.id]: 'run' }))
    lastCorr = ''
    const t0 = performance.now()
    const stamp = (x: Result): Result => ({ ...x, ms: Math.round(performance.now() - t0), at: new Date().toISOString(), corr: lastCorr || undefined })
    try { const x = await c.run(); setRes((r) => ({ ...r, [c.id]: stamp(x) })) } catch (e) { setRes((r) => ({ ...r, [c.id]: stamp({ status: 'error', pass: false, note: (e as Error).message }) })) }
  }
  const runAll = async () => { for (const c of CHECKS) await runOne(c) }
  const done = CHECKS.filter((c) => typeof res[c.id] === 'object')
  const passed = done.filter((c) => (res[c.id] as Result).pass).length
  const [copied, setCopied] = useState(false)

  // Test evidence for a bug tracker: one row per check that ran, with expected vs actual and the correlation id.
  const evidence = () => done.map((c) => ({ c, r: res[c.id] as Result }))
  const downloadCsv = () => {
    const q = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`
    const rows = [['check', 'requirement ids', 'expected', 'actual status', 'result', 'note', 'duration ms', 'run at (UTC)', 'correlation id'],
      ...evidence().map(({ c, r }) => [c.en, c.ids, c.expect, r.status, r.pass ? 'PASS' : 'FAIL', r.note, r.ms, r.at, r.corr])]
    const blob = new Blob(['﻿' + rows.map((row) => row.map(q).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `itms-security-checks-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
  }
  const copyMarkdown = async () => {
    const md = [`**ITMS live security checks** — ${passed}/${done.length} passed, ${new Date().toISOString()}`, '',
      '| Check | IDs | Expected | Actual | Result | Correlation id |', '|---|---|---|---|---|---|',
      ...evidence().map(({ c, r }) => `| ${c.en} | ${c.ids} | ${c.expect} | ${r.status} (${r.ms ?? '—'} ms) ${r.note.replace(/\|/g, '/')} | ${r.pass ? 'PASS' : 'FAIL'} | ${r.corr ?? '—'} |`)].join('\n')
    try { await navigator.clipboard.writeText(md); setCopied(true); setTimeout(() => setCopied(false), 1800) } catch { /* clipboard blocked: the CSV still works */ }
  }

  const flow: [ReactNode, string, string][] = [
    [<Network size={18} key="1" />, 'Client', 'Portal, staff console, partner, assistant (X-Channel)'],
    [<Workflow size={18} key="2" />, 'APISIX gateway', 'Token check (Keycloak keys), rate limit, correlation id, metrics'],
    [<KeyRound size={18} key="3" />, 'Service security', 'Token again + audience; grants and data scopes from iam; step-up for profile changes'],
    [<ShieldCheck size={18} key="4" />, 'Request rules', 'Idempotency-Key, input hardening, maker-checker, RFC 9457 errors'],
    [<Database size={18} key="5" />, 'Own database', 'One PostgreSQL per service; history and audit rows are tamper-protected'],
    [<Radio size={18} key="6" />, 'Outbox → Kafka', 'Events (CloudEvents) to audit hash chain, notifications, reporting'],
  ]

  return (
    <>
      <Card title={ar ? 'فحوص الأمان المباشرة' : 'Live security checks'} icon={<ShieldCheck size={16} />}
        right={<span style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {done.length > 0 && <span className={`pill ${passed === done.length ? 'ok' : 'bad'}`}>{passed}/{done.length} {ar ? 'ناجح' : 'passed'}</span>}
          {done.length > 0 && <button className="btn ghost" onClick={downloadCsv} title={ar ? 'تنزيل الأدلة CSV' : 'Download the evidence as CSV'}><Download size={15} /> CSV</button>}
          {done.length > 0 && <button className="btn ghost" onClick={copyMarkdown} title={ar ? 'نسخ كجدول Markdown لتقرير خطأ' : 'Copy as a Markdown table for a bug report'}>{copied ? <Check size={15} /> : <Copy size={15} />} {copied ? (ar ? 'نُسخ' : 'Copied') : 'Markdown'}</button>}
          <button className="btn primary" onClick={runAll}><Play size={15} /> {ar ? 'تشغيل الكل' : 'Run all'}</button></span>}>
        <div className="qa-table">
          <div className="qa-row head"><span>{ar ? 'الفحص' : 'Check'}</span><span>{ar ? 'المتوقع' : 'Expected'}</span><span>{ar ? 'الفعلي' : 'Actual'}</span><span /></div>
          {CHECKS.map((c) => {
            const r = res[c.id]
            return (
              <div className="qa-row" key={c.id}>
                <span><b>{ar ? c.ar : c.en}</b><small className="mono muted">{c.ids}</small></span>
                <span className="mono">{c.expect}</span>
                <span>{r === 'run' ? <Clock size={15} className="spin" /> : r ? (
                  <span className={`qa-res ${r.pass ? 'ok' : 'bad'}`}>{r.pass ? <CheckCircle2 size={15} /> : <XCircle size={15} />} <b className="mono">{r.status}</b> <small>{r.note}</small>
                    {r.ms !== undefined && <small className="mono muted qa-ev" title={r.corr ? `X-Correlation-Id ${r.corr}` : undefined}>{r.ms} ms{r.corr ? ` · ${r.corr.slice(0, 8)}` : ''}</small>}</span>) : '—'}</span>
                <span><button className="btn ghost" onClick={() => runOne(c)} aria-label={`Run ${c.en}`}><Play size={14} /></button></span>
              </div>
            )
          })}
        </div>
        <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{ar ? 'تعمل الفحوص عبر البوابة الحقيقية. فحص الدفعة يستهلك حصة العنوان لمدة دقيقة.' : 'Checks run through the real gateway. The burst check uses up this address’s public quota for one minute.'}</p>
      </Card>

      <Card title={ar ? 'كيف يمر الطلب (تصميم النظام)' : 'How a request flows (system design)'} icon={<Server size={16} />} className="qa-gap">
        <div className="flow">
          {flow.map(([icon, t, d], i) => (
            <div className="flow-step" key={t}>
              <div className="flow-ico">{icon}</div><b>{t}</b><small>{d}</small>
              {i < flow.length - 1 && <ArrowRight size={16} className="flow-arrow" />}
            </div>
          ))}
        </div>
      </Card>

      <div className="grid g-2 qa-gap" style={{ gridAutoFlow: 'row dense' }}>
        <Card title={ar ? 'قائمة اختبارات القبول' : 'UAT checklist'} icon={<ClipboardCheck size={16} />}>
          <div className="list">
            {UAT.map(([id, what, how, expect, route, auto]) => (
              <div className="row" key={id}>
                <span className="pill ok">{id}</span>
                <div className="grow"><div className="title" style={{ whiteSpace: 'normal' }}>{what}</div>
                  <div className="meta"><span>▶ {how}</span></div><div className="meta"><span>✓ {expect}</span><span className="mono">auto: {auto}</span></div></div>
                <button className="btn" onClick={() => go(route)}>{ar ? 'اختبر' : 'Test'}</button>
              </div>
            ))}
          </div>
        </Card>
        <Card title={ar ? 'دليل اختبار الميزات' : 'Feature test guide'} icon={<ClipboardCheck size={16} />} className="span-2">
          <div className="table-wrap"><table className="tbl">
            <thead><tr><th>{ar ? 'الميزة' : 'Feature'}</th><th>{ar ? 'الخطوات' : 'Steps'}</th><th>{ar ? 'النتيجة المتوقعة' : 'Expected'}</th><th>IDs</th><th /></tr></thead>
            <tbody>{FEATURES.map(([f, how, exp, route, ids]) => (
              <tr key={f}><td><b>{f}</b></td><td className="muted">{how}</td><td>{exp}</td><td className="mono" style={{ fontSize: 11.5, whiteSpace: 'nowrap' }}>{ids}</td>
                <td><button className="btn ghost" onClick={() => go(route)}>{ar ? 'اختبر' : 'Test'}</button></td></tr>))}</tbody>
          </table></div>
        </Card>
        <Card title={ar ? 'قادم في المستقبل' : 'Coming in future'} icon={<Rocket size={16} />}>
          <div className="list">
            {FUTURE.map(([inc, what, state]) => (
              <div className="row" key={what}>
                <span className="rm-n" style={{ width: 30, height: 30 }}>{inc}</span>
                <div className="grow"><div className="title" style={{ whiteSpace: 'normal', fontWeight: 500 }}>{what}</div></div>
                <Future>{state}</Future>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </>
  )
}
