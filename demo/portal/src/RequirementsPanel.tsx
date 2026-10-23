import { useEffect, useMemo, useState } from 'react'
import { ListChecks, ChevronRight, AlertCircle, CheckCircle2, Clock } from 'lucide-react'

// Compact live requirements panel for the sidebar: counts per status, per-module progress bars,
// and a "view all" link. Reads the same /demo/ledger the requirements page reads.
type Row = { status: string; id: string; priority: string; text: string; implementation: string; test: string; notes: string; shownIn: string[] }

const ICON: Record<string, React.ReactNode> = {
  '[x]': <CheckCircle2 size={14} strokeWidth={2.5} />,
  '[~]': <Clock size={14} strokeWidth={2.5} />,
  '[ ]': <AlertCircle size={14} strokeWidth={2.5} />,
}

export function RequirementsPanel({ go, lang }: { go: (r: string) => void; lang: string }) {
  const ar = lang === 'ar'
  const [rows, setRows] = useState<Row[] | null>(null)

  useEffect(() => {
    fetch('/demo/ledger')
      .then((r) => r.json())
      .then((d) => (d.rows ? setRows(d.rows) : setRows([])))
      .catch(() => setRows([]))
  }, [])

  // Hooks must run on every render with the same order: compute from rows ?? [] before any early return.
  const byModule = useMemo(() => {
    const m: Record<string, { v: number; p: number; n: number }> = {}
    for (const r of rows ?? []) {
      const pre = r.id.split('-')[0]
      if (!m[pre]) m[pre] = { v: 0, p: 0, n: 0 }
      if (r.status === '[x]') m[pre].v++
      else if (r.status === '[~]') m[pre].p++
      else m[pre].n++
    }
    return Object.entries(m).sort((a, b) => (b[1].v + b[1].p) - (a[1].v + a[1].p))
  }, [rows])

  if (!rows) return null

  const total = rows.length
  const verified = rows.filter((r) => r.status === '[x]').length
  const progress = rows.filter((r) => r.status === '[~]').length
  const pending = rows.filter((r) => r.status === '[ ]').length
  const pct = total > 0 ? Math.round((verified / total) * 100) : 0

  return (
    <section className="req-panel page-enter" aria-label={ar ? 'مستكشف المتطلبات' : 'Requirements explorer'}>
      <div className="req-panel-head" onClick={() => go('requirements')}>
        <ListChecks size={16} />
        <span>{ar ? 'المتطلبات' : 'Requirements'}</span>
        <span className="req-panel-arrow"><ChevronRight size={12} /></span>
      </div>

      <div className="req-panel-stats">
        <div className="req-stat">
          <div className="req-big">{pct}</div>
          <div className="req-small">{ar ? 'متحقق' : 'verified'}</div>
        </div>
        <div className="req-stat">
          <div className="req-big">{progress}</div>
          <div className="req-small">{ar ? 'قيد التنفيذ' : 'in progress'}</div>
        </div>
        <div className="req-stat">
          <div className="req-big">{pending}</div>
          <div className="req-small">{ar ? 'لم يبدأ' : 'not started'}</div>
        </div>
      </div>

      <div className="req-panel-progress">
        <div className="req-bar-wrap" style={{ height: 7 }}>
          <div className="req-bar-fill" style={{ width: `${pct}%`, background: 'var(--uae-green)' }} />
        </div>
        <div className="req-bar-label" style={{ display: 'flex', gap: 10, fontSize: 11, color: 'var(--muted)', marginTop: 4, justifyContent: 'space-between' }}>
          <span>{verified} / {total}</span>
          <span>{ar ? 'من إجمالي المتطلبات' : 'of total requirements'}</span>
        </div>
      </div>

      <div className="req-panel-modules">
        <div className="req-subhead">{ar ? 'بالوحدة' : 'By module'}</div>
        {byModule.slice(0, 6).map(([pre, c]) => {
          const t = c.v + c.p + c.n
          const vp = t > 0 ? Math.round(((c.v + c.p) / t) * 100) : 0
          return (
            <div key={pre} className="req-module-row">
              <span className="req-module-name" style={{ fontSize: 11.5, color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{pre}</span>
              <div className="req-module-bar" style={{ flex: 1, height: 5, borderRadius: 3, background: 'rgba(148,163,214,0.12)', overflow: 'hidden', margin: '0 8px' }}>
                <div className="req-module-fill" style={{ width: `${vp}%`, height: '100%', background: vp >= 70 ? 'var(--uae-green)' : vp >= 40 ? 'var(--uae-amber)' : 'var(--uae-red)' }} />
              </div>
              <span className="req-module-count" style={{ fontSize: 10, color: 'var(--muted)', minWidth: 24, textAlign: 'end' }}>{c.v + c.p}/{t}</span>
            </div>
          )
        })}
        <button className="req-view-all" onClick={() => go('requirements')}>
          {ICON['[x]']} {ar ? 'عرض الكل' : 'View all requirements'}
        </button>
      </div>
    </section>
  )
}
