import { Fragment, useEffect, useMemo, useState } from 'react'
import { ChevronDown, ChevronRight, ListChecks, Play, Search } from 'lucide-react'
import { Card, Kpi, Skeleton } from '../components'
import { useI18n } from '../i18n'

// Requirements explorer: all 438 BRD requirements with the status of record (docs/brief/01 ledger, read live by the
// dev server), the evidence (code, tests, notes) and, where a portal screen shows the requirement, a "Try it" link.
// The page only reads; a requirement's status changes in the ledger, never here.

type Row = { status: string; id: string; priority: string; text: string; implementation: string; test: string; notes: string; shownIn: string[] }
const MODULES: [string, string, string][] = [
  ['CC', 'Shared services', 'الخدمات المشتركة'], ['DL', 'Traffic file, licensing, exams', 'الملف المروري والرخص والاختبارات'],
  ['VL', 'Vehicles and plates', 'المركبات واللوحات'], ['VF', 'Violations and black points', 'المخالفات والنقاط السوداء'],
  ['FN', 'Payments and revenue', 'المدفوعات والإيرادات'], ['AC', 'Accidents', 'الحوادث'], ['TR', 'Training permits, rental', 'تصاريح التدريب والتأجير'],
  ['RE', 'Road engineering, marine', 'هندسة الطرق والملاحة'], ['XB', 'Cross-border', 'التبادل عبر الحدود'], ['CH', 'Digital channels', 'القنوات الرقمية'],
  ['AI', 'AI and agents', 'الذكاء الاصطناعي'], ['INT', 'Integration', 'التكامل'], ['RPT', 'Reports', 'التقارير'], ['DAT', 'Data', 'البيانات'],
  ['NFR', 'Non-functional', 'غير وظيفية'], ['SEC', 'Security', 'الأمن'], ['DLV', 'Delivery', 'التسليم'],
]
const LABEL: Record<string, [string, string, string]> = {
  '[x]': ['Verified', 'متحقق', 'ok'], '[~]': ['In progress', 'قيد التنفيذ', 'info'], '[ ]': ['Not started', 'لم يبدأ', 'future'],
  '[!]': ['Blocked', 'متوقف', 'bad'], '[?]': ['Needs business input', 'بحاجة لقرار', 'warn'],
}
const PAGE: Record<string, [string, string]> = {
  home: ['Overview', 'نظرة عامة'], dashboard: ['My dashboard', 'لوحتي'], profile: ['My profile', 'ملفي'], documents: ['My documents', 'مستنداتي'],
  services: ['My services', 'خدماتي'], settings: ['Contact & alerts', 'التواصل'], assistant: ['Assistant', 'المساعد'], control: ['Control room', 'غرفة التحكم'],
  backoffice: ['Back office', 'المكتب الخلفي'], 'backoffice?tab=newfile': ['New file', 'ملف جديد'], 'backoffice?tab=applications': ['Applications', 'الطلبات'],
  modules: ['Modules', 'الوحدات'], analytics: ['Analytics', 'التحليلات'], reports: ['Reports', 'التقارير'], admin: ['Administration', 'الإدارة'],
  platform: ['Platform', 'المنصة'], qa: ['QA & security', 'الجودة والأمان'], guide: ['Service guide', 'دليل الخدمات'],
}

export default function Requirements({ go }: { go: (r: string) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [rows, setRows] = useState<Row[] | null>(null)
  const [err, setErr] = useState('')
  const [q, setQ] = useState('')
  const [mod, setMod] = useState('')
  const [st, setSt] = useState('')
  const [onlyShown, setOnlyShown] = useState(false)
  const [open, setOpen] = useState<string | null>(null)

  useEffect(() => {
    fetch('/demo/ledger').then((r) => r.json()).then((d) => (d.rows ? setRows(d.rows) : setErr(d.detail ?? 'ledger unavailable'))).catch((e) => setErr(String(e)))
  }, [])

  const count = (s: string, list = rows ?? []) => list.filter((r) => r.status === s).length
  const list = useMemo(() => (rows ?? []).filter((r) => (!mod || r.id.split('-')[0] === mod) && (!st || r.status === st) && (!onlyShown || r.shownIn.length > 0)
    && (!q || `${r.id} ${r.text} ${r.implementation}`.toLowerCase().includes(q.toLowerCase()))), [rows, mod, st, onlyShown, q])

  if (err) return <div className="card empty">{err}</div>
  if (!rows) return <Card title="…"><Skeleton lines={6} /></Card>

  return (
    <>
      <div className="grid g-4" style={{ marginBottom: 18 }}>
        <Kpi icon={<ListChecks size={18} />} value={rows.length} label={ar ? 'متطلبات الوثيقة' : 'BRD requirements'} />
        <Kpi tone="green" icon={<ListChecks size={18} />} value={count('[x]')} label={ar ? 'متحقق' : 'Verified'} />
        <Kpi tone="violet" icon={<ListChecks size={18} />} value={count('[~]')} label={ar ? 'قيد التنفيذ' : 'In progress'} />
        <Kpi tone="amber" icon={<Play size={18} />} value={rows.filter((r) => r.shownIn.length).length} label={ar ? 'يمكن تجربتها في العرض' : 'Shown in the demo'} />
      </div>

      <Card title={ar ? 'التقدم حسب الوحدة' : 'Progress by module'} icon={<ListChecks size={16} />} right={<span className="pill">{ar ? 'اضغط وحدة للتصفية' : 'Click a module to filter'}</span>}>
        <div className="req-mods">{MODULES.map(([p, en, a]) => {
          const m = rows.filter((r) => r.id.startsWith(`${p}-`)); const x = count('[x]', m), w = count('[~]', m)
          return (
            <button key={p} className={`req-mod ${mod === p ? 'on' : ''}`} onClick={() => setMod(mod === p ? '' : p)} title={`${x} verified, ${w} in progress, ${m.length - x - w} not started`}>
              <span className="req-mod-name"><b>{p}</b> {ar ? a : en}</span>
              <span className="req-bar" aria-hidden="true"><i className="x" style={{ width: `${(x / m.length) * 100}%` }} /><i className="w" style={{ width: `${(w / m.length) * 100}%` }} /></span>
              <span className="mono muted">{x + w}/{m.length}</span>
            </button>)
        })}</div>
      </Card>

      <Card title={ar ? 'كل المتطلبات' : 'All requirements'} icon={<Search size={16} />} className="qa-gap" right={<span className="pill">{list.length}</span>}>
        <div className="req-filters">
          <input placeholder={ar ? 'بحث: رقم أو كلمة (مثل VF-030 أو insurance)' : 'Search: an ID or a word (e.g. VF-030, insurance)'} value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search requirements" />
          <select value={st} onChange={(e) => setSt(e.target.value)} aria-label="Status">
            <option value="">{ar ? 'كل الحالات' : 'Every status'}</option>
            {['[x]', '[~]', '[ ]'].map((s) => <option key={s} value={s}>{ar ? LABEL[s][1] : LABEL[s][0]} ({count(s)})</option>)}
          </select>
          <select value={mod} onChange={(e) => setMod(e.target.value)} aria-label="Module">
            <option value="">{ar ? 'كل الوحدات' : 'Every module'}</option>
            {MODULES.map(([p, en, a]) => <option key={p} value={p}>{p} · {ar ? a : en}</option>)}
          </select>
          <label className="req-check"><input type="checkbox" checked={onlyShown} onChange={(e) => setOnlyShown(e.target.checked)} /> {ar ? 'المعروضة في العرض فقط' : 'Only those shown in the demo'}</label>
        </div>
        {ar && <p className="muted" style={{ fontSize: 12 }}>نص المتطلبات بالإنجليزية كما ورد في وثيقة المتطلبات.</p>}
        <div className="table-wrap"><table className="tbl req-tbl">
          <thead><tr><th /><th>ID</th><th>{ar ? 'المتطلب' : 'Requirement'}</th><th>{ar ? 'الأولوية' : 'Pri.'}</th><th>{ar ? 'الحالة' : 'Status'}</th><th>{ar ? 'جرّبه' : 'Try it'}</th></tr></thead>
          <tbody>{list.map((r) => {
            const [en, a, tone] = LABEL[r.status] ?? [r.status, r.status, 'info']
            return (
              <Fragment key={r.id}>
                <tr className="clickable" onClick={() => setOpen(open === r.id ? null : r.id)}>
                  <td>{open === r.id ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</td>
                  <td className="mono" style={{ whiteSpace: 'nowrap' }}>{r.id}</td>
                  <td dir="ltr" style={{ textAlign: 'start' }}>{r.text}</td>
                  <td className="mono">{r.priority}</td>
                  <td><span className={`pill ${tone}`}>{ar ? a : en}</span></td>
                  <td onClick={(e) => e.stopPropagation()} style={{ whiteSpace: 'nowrap' }}>{r.shownIn.map((p) => (
                    <button key={p} className="btn ghost" style={{ padding: '4px 8px' }} onClick={() => go(p)}><Play size={12} /> {PAGE[p]?.[ar ? 1 : 0] ?? p}</button>))}</td>
                </tr>
                {open === r.id && (
                  <tr className="req-detail"><td /><td colSpan={5} dir="ltr">
                    <dl className="kv" style={{ margin: 0 }}>
                      <dt>Implementation</dt><dd>{r.implementation || '—'}</dd>
                      <dt>Tests</dt><dd>{r.test || '—'}</dd>
                      <dt>Evidence / notes</dt><dd>{r.notes || '—'}</dd>
                    </dl>
                  </td></tr>
                )}
              </Fragment>)
          })}</tbody>
        </table></div>
      </Card>
    </>
  )
}
