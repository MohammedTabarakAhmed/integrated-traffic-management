import { Component, useEffect, useState, type ReactNode } from 'react'
import { useI18n } from './i18n'
import { sentence } from './names'

/** The tab a link asks for (#/page?tab=x), if it is one of the page's tabs; otherwise the page's default. */
export function tabFromLink<T extends string>(allowed: readonly T[], fallback: T): T {
  const want = new URLSearchParams(location.hash.split('?')[1] ?? '').get('tab')
  return (allowed as readonly string[]).includes(want ?? '') ? (want as T) : fallback
}

/** Keeps a page's render error inside that page: the navigation stays usable and "Try again" re-mounts the page. */
export class PageBoundary extends Component<{ children: ReactNode; ar: boolean }, { error: Error | null }> {
  state = { error: null as Error | null }
  static getDerivedStateFromError(error: Error) { return { error } }
  componentDidCatch(error: Error) { console.error('[ITMS] page error', error) }
  render() {
    if (!this.state.error) return this.props.children
    const { ar } = this.props
    return (
      <div className="banner" role="alert" style={{ margin: 0 }}>
        <div className="grow">
          <b>{ar ? 'تعذّر عرض هذه الصفحة' : 'This page could not be shown'}</b>
          <div className="muted" style={{ fontSize: 13 }}>{this.state.error.message}</div>
        </div>
        <button className="btn" onClick={() => this.setState({ error: null })}>{ar ? 'حاول مجددًا' : 'Try again'}</button>
      </div>
    )
  }
}

/** Semicircle points gauge; turns red near the threshold. */
export function Gauge({ value, max, label }: { value: number; max: number; label: string }) {
  const [shown, setShown] = useState(0)
  useEffect(() => {
    const id = requestAnimationFrame(() => setShown(Math.min(value, max)))
    return () => cancelAnimationFrame(id)
  }, [value, max])
  const r = 90
  const len = Math.PI * r
  const ratio = max > 0 ? shown / max : 0
  const danger = value >= max * 0.75
  const ticks = Array.from({ length: 9 }, (_, i) => i / 8)
  return (
    <div className={`gauge ${danger ? 'danger' : ''}`} role="meter" aria-valuemin={0} aria-valuemax={max} aria-valuenow={value} aria-label={label}>
      <svg viewBox="-110 -110 220 130">
        <defs>
          <linearGradient id="g-ok" x1="0" x2="1">
            <stop offset="0%" stopColor="#22d3ee" />
            <stop offset="60%" stopColor="#6366f1" />
            <stop offset="100%" stopColor="#a855f7" />
          </linearGradient>
          <linearGradient id="g-bad" x1="0" x2="1">
            <stop offset="0%" stopColor="#fbbf24" />
            <stop offset="100%" stopColor="#f43f5e" />
          </linearGradient>
        </defs>
        {ticks.map((f) => {
          const a = Math.PI * (1 - f)
          return (
            <line key={f} x1={Math.cos(a) * 104} y1={-Math.sin(a) * 104} x2={Math.cos(a) * 110} y2={-Math.sin(a) * 110}
              stroke="rgba(148,163,214,0.35)" strokeWidth="1.5" />
          )
        })}
        <path className="track" d={`M ${-r} 0 A ${r} ${r} 0 0 1 ${r} 0`} fill="none" strokeWidth="14" strokeLinecap="round" />
        <path className="fill" d={`M ${-r} 0 A ${r} ${r} 0 0 1 ${r} 0`} fill="none" strokeWidth="14" strokeLinecap="round"
          stroke={`url(#${danger ? 'g-bad' : 'g-ok'})`} strokeDasharray={len} strokeDashoffset={len * (1 - ratio)} />
      </svg>
      <div className="value">
        <b className={danger ? '' : 'grad-text'} style={danger ? { color: 'var(--red)' } : undefined}>{value}</b>
        <span>{label}</span>
      </div>
    </div>
  )
}

export function Card({ title, icon, right, children, className = '' }: {
  title?: ReactNode; icon?: ReactNode; right?: ReactNode; children: ReactNode; className?: string
}) {
  return (
    <section className={`card ${className}`}>
      {title && <h3>{icon}{title}{right && <span className="right">{right}</span>}</h3>}
      {children}
    </section>
  )
}

export function Kpi({ icon, value, label, tone = '' }: { icon: ReactNode; value: ReactNode; label: string; tone?: string }) {
  return (
    <section className={`card kpi ${tone}`}>
      <div className="icon">{icon}</div>
      <div className="v">{value}</div>
      <div className="l">{label}</div>
    </section>
  )
}

export function StatusPill({ s }: { s: string }) {
  const { t } = useI18n()
  const tone = ['PAID', 'COMPLETED', 'ACTIVE', 'DELIVERED', 'SENT', 'BOOKED', 'ATTENDED', 'ISSUED', 'APPROVED'].includes(s)
    ? 'ok' : ['OPEN', 'PENDING', 'SUBMITTED', 'IN_PROGRESS', 'PENDING_APPROVAL'].includes(s)
      ? 'warn' : ['CANCELLED', 'REJECTED', 'FAILED'].includes(s) ? 'bad' : 'info'
  return <span className={`pill ${tone}`}>{t.status[s] ?? sentence(s)}</span>
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return <>{Array.from({ length: lines }, (_, i) => <div key={i} className="skeleton" style={{ width: `${90 - i * 15}%` }} />)}</>
}

/** Counts up to a number for a little life on first paint. */
export function CountUp({ to, ms = 900 }: { to: number; ms?: number }) {
  const [v, setV] = useState(0)
  useEffect(() => {
    let raf = 0
    const start = performance.now()
    const tick = (now: number) => {
      const p = Math.min(1, (now - start) / ms)
      setV(Math.round(to * (1 - Math.pow(1 - p, 3))))
      if (p < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [to, ms])
  return <>{v.toLocaleString()}</>
}

/** Slide-in panel for details (closes on Escape or the backdrop). */
export function Drawer({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode }) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    addEventListener('keydown', onKey)
    return () => removeEventListener('keydown', onKey)
  }, [open, onClose])
  if (!open) return null
  return (
    <div className="drawer-back" onClick={onClose}>
      <aside className="drawer" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="drawer-head"><h2>{title}</h2><button className="btn ghost" onClick={onClose} aria-label="Close">✕</button></div>
        <div className="drawer-body">{children}</div>
      </aside>
    </div>
  )
}

/** Vertical timeline of status changes or history entries. */
export function Timeline({ items }: { items: { title: ReactNode; meta?: ReactNode; tone?: 'ok' | 'warn' | 'bad' | 'info' }[] }) {
  return (
    <ol className="vtl">
      {items.map((it, i) => (
        <li key={i} className={it.tone ?? 'info'}><span className="vtl-dot" /><div><div className="title">{it.title}</div>{it.meta && <div className="meta">{it.meta}</div>}</div></li>
      ))}
    </ol>
  )
}
