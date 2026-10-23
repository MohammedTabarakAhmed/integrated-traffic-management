import { useEffect, useState } from 'react'
import { Wifi, Server, Database, ShieldAlert } from 'lucide-react'

// Live service health monitor for the sidebar: polls /demo/health and shows up/down per group
// with a small dot per service. Practical for a remote tester watching the demo.
type Service = { name: string; port: number; group: string; up: boolean | null; ms: number | null }

const GROUP_ICON: Record<string, React.ReactNode> = {
  shared: <Server size={13} />,
  vehicles: <Database size={13} />,
  violations: <ShieldAlert size={13} />,
  licensing: <Wifi size={13} />,
  revenue: <ShieldAlert size={13} />,
  accidents: <ShieldAlert size={13} />,
  permits: <Server size={13} />,
  road: <Database size={13} />,
  crossborder: <ShieldAlert size={13} />,
  channels: <Wifi size={13} />,
  analytics: <Server size={13} />,
}

export function HealthPanel({ lang }: { lang: string }) {
  const ar = lang === 'ar'
  const [services, setServices] = useState<Service[]>([])

  useEffect(() => {
    const tick = async () => {
      try {
        const d = await fetch('/demo/health').then((r) => r.json())
        const svc: Service[] = ((d?.services ?? []) as { name: string; port: number; group: string; up: boolean | null; ms: number | null }[]).map((s) => ({
          name: s.name, port: s.port, group: s.group,
          up: s.up ?? null, ms: s.ms ?? null,
        }))
        setServices(svc)
      } catch { /* ignore: stack may be down */ }
    }
    tick()
    const t = setInterval(tick, 15000)
    return () => clearInterval(t)
  }, [])

  const groups = Array.from(new Set(services.map((s) => s.group)))
  const up = services.filter((s) => s.up).length
  const total = services.length
  const pct = total > 0 ? Math.round((up / total) * 100) : 0

  return (
    <section className="req-panel page-enter" style={{ marginTop: 10 }} aria-label={ar ? 'حالة الخدمات' : 'Service health'}>
      <div className="req-panel-head" style={{ cursor: 'default' }}>
        <Server size={16} />
        <span>{ar ? 'حالة الخدمات' : 'Service health'}</span>
        <span className="req-panel-arrow" style={{ color: pct === 100 ? 'var(--uae-green)' : pct > 0 ? 'var(--uae-amber)' : 'var(--uae-red)' }}>
          {pct === 100 ? <span style={{ fontSize: 10 }}>●</span> : pct > 0 ? <span style={{ fontSize: 10 }}>◐</span> : <span style={{ fontSize: 10 }}>○</span>}
        </span>
      </div>
      <div className="req-panel-stats" style={{ gap: 8 }}>
        <div className="req-stat">
          <div className="req-big" style={{ color: pct === 100 ? 'var(--uae-green)' : pct > 0 ? 'var(--uae-amber)' : 'var(--uae-red)' }}>{pct}</div>
          <div className="req-small">{ar ? 'معدل التشغيل' : 'uptime'}</div>
        </div>
        <div className="req-stat">
          <div className="req-big">{up}</div>
          <div className="req-small">{ar ? 'مفتوحة' : 'up'}</div>
        </div>
        <div className="req-stat">
          <div className="req-big">{total}</div>
          <div className="req-small">{ar ? 'خدمة' : 'services'}</div>
        </div>
      </div>
      <div className="req-panel-modules" style={{ paddingTop: 4 }}>
        <div className="req-subhead">{ar ? 'الخدمات حسب المجموعة' : 'Services by group'}</div>
        {groups.map((g) => {
          const gs = services.filter((s) => s.group === g)
          const gUp = gs.filter((s) => s.up).length
          const gPct = gs.length > 0 ? Math.round((gUp / gs.length) * 100) : 0
          return (
            <div key={g} className="req-module-row">
              <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--muted)', whiteSpace: 'nowrap' }}>
                {GROUP_ICON[g] ?? <Server size={11} />}{g}
              </span>
              <div className="req-module-bar" style={{ width: 60, height: 4, borderRadius: 2, background: 'rgba(148,163,214,0.12)', overflow: 'hidden' }}>
                <div className="req-module-fill" style={{ width: `${gPct}%`, height: '100%', background: gPct === 100 ? 'var(--uae-green)' : gPct > 0 ? 'var(--uae-amber)' : 'var(--uae-red)' }} />
              </div>
              <span style={{ fontSize: 10, color: 'var(--muted)', minWidth: 24, textAlign: 'end' }}>{gUp}/{gs.length}</span>
            </div>
          )
        })}
        <div style={{ fontSize: 10, color: 'var(--dim)', marginTop: 4 }}>{ar ? 'يُحدّث كل 15 ثانية' : 'Refreshes every 15s'}</div>
      </div>
    </section>
  )
}
