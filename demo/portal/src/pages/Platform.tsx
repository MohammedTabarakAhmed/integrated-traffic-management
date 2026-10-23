import { DemoScript } from '../DemoScript'
import { useEffect, useState } from 'react'
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'
import { Boxes, Braces, Database, GitBranch, KeyRound, Network, Radio, ShieldCheck, Smartphone, Workflow } from 'lucide-react'
import { Card } from '../components'
import { useI18n } from '../i18n'

// Platform (Increments 0 and 14): the architecture as it runs now (probe of every service plus Prometheus request
// rates), the increment roadmap and the engineering evidence. Figures are those recorded in the project guide §2-§3.

type Svc = { name: string; port: number; group: string; up: boolean; ms: number | null }
type Health = { services: Svc[]; infra: { name: string; up: boolean; ms: number | null }[] }

const GROUPS: [string, string, string][] = [
  ['shared', 'Shared services', 'الخدمات المشتركة'], ['vehicles', 'Vehicles', 'المركبات'], ['licensing', 'Licensing', 'الترخيص'],
  ['violations', 'Violations', 'المخالفات'], ['revenue', 'Revenue', 'الإيرادات'], ['accidents', 'Accidents', 'الحوادث'],
  ['permits', 'Permits & rental', 'التصاريح والتأجير'], ['road', 'Road & marine', 'الطرق والملاحة'], ['crossborder', 'Cross-border', 'عبر الحدود'],
  ['channels', 'Channels', 'القنوات'], ['analytics', 'Analytics', 'التحليلات'],
]

// State per increment as recorded in the project guide §3 (code done / partial / not started); the demo is not acceptance.
const ROADMAP: [string, string, string, 'done' | 'partial' | 'next'][] = [
  ['0', 'Walking skeleton', 'الهيكل الأولي', 'done'], ['1', 'Shared services', 'الخدمات المشتركة', 'done'],
  ['2', 'Vehicles & plates', 'المركبات واللوحات', 'done'], ['3', 'Licensing & exams', 'الترخيص والاختبارات', 'done'],
  ['4', 'Violations', 'المخالفات', 'partial'], ['5', 'Revenue', 'الإيرادات', 'partial'], ['6', 'Accidents', 'الحوادث', 'partial'],
  ['7', 'Permits & rental', 'التصاريح والتأجير', 'partial'], ['8', 'Road & marine', 'الطرق والملاحة', 'partial'],
  ['9', 'Cross-border', 'عبر الحدود', 'done'], ['10', 'Digital channels & UI', 'القنوات الرقمية', 'partial'],
  ['11', 'Reporting & analytics', 'التقارير والتحليلات', 'partial'], ['12', 'Data migration', 'ترحيل البيانات', 'next'],
  ['13', 'AI & agents', 'الذكاء الاصطناعي', 'next'], ['14', 'Hardening & acceptance', 'التحصين والقبول', 'next'],
]

const EVIDENCE: [string, string, string][] = [
  ['833', 'automated tests (unit, integration, contract)', 'اختبارًا آليًا'],
  ['29', 'Maven modules, clean -Psast verify', 'وحدة Maven'],
  ['57', 'smoke steps green on the running stack', 'خطوة دخان ناجحة'],
  ['≥80%', 'business-logic coverage gate (JaCoCo)', 'حد التغطية'],
]
const TOOLS = ['ArchUnit layers', 'SpotBugs + FindSecBugs', 'OWASP Dependency-Check', 'CycloneDX SBOM', 'gitleaks', 'OpenAPI 3.1 lint', 'Testcontainers', 'RFC 9457 errors', 'Idempotency-Key', 'Transactional outbox', 'Hash-chained audit', 'Maker-checker']
const REQS = [{ name: 'Verified', ar: 'متحقق', value: 126, color: '#34d399' }, { name: 'In progress', ar: 'قيد التنفيذ', value: 140, color: '#6366f1' }, { name: 'Not started', ar: 'لم يبدأ', value: 172, color: '#334155' }]

export default function Platform() {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [h, setH] = useState<Health | null>(null)
  const [rps, setRps] = useState<Record<string, number>>({})

  useEffect(() => {
    const tick = () => {
      fetch('/demo/health').then((r) => r.json()).then(setH).catch(() => {})
      const q = encodeURIComponent('sum by (instance) (rate(http_server_requests_seconds_count[2m]))')
      fetch(`/prom/api/v1/query?query=${q}`).then((r) => r.json()).then((d) => {
        const m: Record<string, number> = {}
        for (const r of d.data?.result ?? []) m[String(r.metric.instance).replace('-service:8090', '')] = Number(r.value[1])
        setRps(m)
      }).catch(() => {})
    }
    tick()
    const id = setInterval(tick, 5000)
    return () => clearInterval(id)
  }, [])

  const up = h ? h.services.filter((s) => s.up).length : 0
  return (
    <>
      <Card title={ar ? 'البنية الحية' : 'Live architecture'} icon={<Network size={16} />}
        right={<span className={`pill ${h && up === h.services.length ? 'ok' : 'warn'}`}><span className="dot live" /> {up}/{h?.services.length ?? 27} {ar ? 'خدمة تعمل' : 'services up'}</span>}>
        <div className="arch">
          <div className="arch-row">
            {[['Citizen portal', 'بوابة المتعامل', <Smartphone size={15} key="a" />], ['Staff console', 'وحدة الموظفين', <Boxes size={15} key="b" />], ['Partners', 'الشركاء', <Radio size={15} key="c" />]].map(([en, a, icon]) => (
              <div key={en as string} className="arch-chip">{icon}{ar ? a : en}</div>))}
          </div>
          <div className="arch-flow" />
          <div className="arch-row">
            {(h?.infra ?? [{ name: 'gateway', up: true, ms: null }, { name: 'keycloak', up: true, ms: null }]).filter((i) => i.name !== 'prometheus').map((i) => (
              <div key={i.name} className={`arch-chip infra ${i.up ? '' : 'down'}`}>{i.name === 'gateway' ? <Workflow size={15} /> : <KeyRound size={15} />}
                {i.name === 'gateway' ? 'APISIX gateway' : 'Keycloak SSO'}<span className="dot" /></div>))}
          </div>
          <div className="arch-flow" />
          <div className="arch-groups">
            {GROUPS.map(([g, en, a]) => {
              const list = (h?.services ?? []).filter((s) => s.group === g)
              return (
                <div key={g} className={`arch-group ${g === 'shared' ? 'wide' : ''}`}>
                  <div className="arch-group-title">{ar ? a : en}</div>
                  {list.map((s) => (
                    <div key={s.name} className={`svc ${s.up ? '' : 'down'}`} title={`${s.name}-service :${s.port}`}>
                      <span className="dot" /><span className="grow">{s.name}</span>
                      <span className="mono muted">{rps[s.name] !== undefined ? `${rps[s.name].toFixed(1)}/s` : s.ms !== null ? `${s.ms}ms` : '—'}</span>
                    </div>
                  ))}
                </div>
              )
            })}
          </div>
          <div className="arch-flow" />
          <div className="arch-row">
            <div className="arch-chip bus"><Radio size={15} /> Kafka · CloudEvents · {ar ? 'صندوق صادر معاملاتي' : 'transactional outbox'}</div>
            <div className="arch-chip"><Database size={15} /> PostgreSQL × 27 · {ar ? 'قاعدة لكل خدمة' : 'one DB per service'}</div>
            <div className="arch-chip"><ShieldCheck size={15} /> OpenBao · MinIO · ClamAV · OTel</div>
          </div>
        </div>
      </Card>
      <div className="grid g-3" style={{ marginTop: 18 }}>
        <Card className="span-2" title={ar ? 'خارطة الطريق: المراحل 0-14' : 'Roadmap: increments 0-14'} icon={<GitBranch size={16} />}>
          <div className="roadmap">
            {ROADMAP.map(([n, en, a, st]) => (
              <div key={n} className={`rm ${st}`}>
                <span className="rm-n">{n}</span>
                <span className="rm-t">{ar ? a : en}</span>
                <span className={`pill ${st === 'done' ? 'ok' : st === 'partial' ? 'info' : 'future'}`}>{st === 'done' ? (ar ? 'مبني' : 'built') : st === 'partial' ? (ar ? 'جزئي' : 'partial') : (ar ? 'قادم' : 'coming')}</span>
              </div>
            ))}
          </div>
        </Card>
        <Card title={ar ? 'متطلبات وثيقة الأعمال (438)' : 'BRD requirements (438)'} icon={<ShieldCheck size={16} />}>
          <div style={{ height: 190, direction: 'ltr' }}>
            <ResponsiveContainer>
              <PieChart>
                <Pie data={REQS} dataKey="value" innerRadius={55} outerRadius={80} paddingAngle={3} stroke="none">
                  {REQS.map((r) => <Cell key={r.name} fill={r.color} />)}
                </Pie>
                <Tooltip contentStyle={{ background: '#111a2e', border: '1px solid rgba(148,163,214,0.28)', borderRadius: 12 }} itemStyle={{ color: '#e8edf9' }} />
              </PieChart>
            </ResponsiveContainer>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'center' }}>
            {REQS.map((r) => <span key={r.name} className="pill"><span className="dot" style={{ color: r.color }} /> {ar ? r.ar : r.name} {r.value}</span>)}
          </div>
          <p className="muted" style={{ fontSize: 12, marginBottom: 0, textAlign: 'center' }}>{ar ? 'تتبع كامل من المتطلب إلى الكود والاختبار' : 'Every requirement traced to code and tests'}</p>
        </Card>
      </div>
      <div className="grid g-4" style={{ marginTop: 18 }}>
        {EVIDENCE.map(([v, en, a]) => (
          <section key={en} className="card kpi"><div className="v grad-text" dir="ltr" style={{ textAlign: 'start' }}>{v}</div><div className="l">{ar ? a : en}</div></section>
        ))}
      </div>
      <Card title={ar ? 'الهندسة والأمان' : 'Engineering & security built in'} icon={<ShieldCheck size={16} />} className="tools-card">
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>{TOOLS.map((t) => <span key={t} className="pill info">{t}</span>)}
          <a className="btn primary" style={{ marginInlineStart: 'auto' }} href="/api.html" target="_blank" rel="noreferrer"><Braces size={16} /> {ar ? 'افتح مستكشف الواجهات (27 مواصفة)' : 'Open the API explorer (27 specs)'}</a></div>
      </Card>

      <DemoScript go={(r) => { window.location.hash = `#/${r}` }} lang={lang} />
    </>
  )
}
