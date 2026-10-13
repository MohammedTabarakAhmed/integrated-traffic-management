import { LayoutDashboard, Radar, ShieldCheck } from 'lucide-react'
import { CountUp } from '../components'
import { useI18n } from '../i18n'

// Service names around the core; the angle spreads them over two rings.
const inner = ['iam', 'traffic-file', 'violation', 'payment', 'vehicle', 'licensing', 'notification', 'audit']
const outer = ['gateway', 'keycloak', 'kafka', 'accident', 'permit', 'rental', 'marine', 'crossborder', 'search', 'reporting', 'workflow', 'rules']

function place(names: string[], radius: number, offset = 0) {
  return names.map((n, i) => {
    const a = (i / names.length) * Math.PI * 2 + offset
    return { n, left: `${50 + Math.cos(a) * radius}%`, top: `${50 + Math.sin(a) * radius}%` }
  })
}

export default function Home({ go, onCustomer }: { go: (r: string) => void; onCustomer: () => void }) {
  const { t } = useI18n()
  return (
    <div className="hero">
      <div>
        <span className="pill info"><span className="dot live" /> {t.brandLong}</span>
        <h2>{t.heroA} <span className="grad-text">{t.heroB}</span></h2>
        <p>{t.tagline}</p>
        <div className="ctas">
          <button className="btn primary lg" onClick={onCustomer}><ShieldCheck size={18} /> {t.signInNational}</button>
          <button className="btn lg" onClick={() => go('control')}><Radar size={18} /> {t.staffCta}</button>
          <button className="btn ghost lg" onClick={() => go('dashboard')}><LayoutDashboard size={18} /> {t.heroCta}</button>
        </div>
        <div className="stats">
          <div className="stat"><b className="grad-text"><CountUp to={27} /></b><span>{t.stats.services}</span></div>
          <div className="stat"><b className="grad-text"><CountUp to={833} /></b><span>{t.stats.tests}</span></div>
          <div className="stat"><b className="grad-text"><CountUp to={438} /></b><span>{t.stats.reqs}</span></div>
          <div className="stat"><b className="grad-text"><CountUp to={27} /></b><span>{t.stats.dbs}</span></div>
        </div>
      </div>
      <div className="orbit" aria-hidden="true">
        <div className="ring r1" />
        <div className="ring r2" />
        <div className="core">ITMS</div>
        {place(inner, 30, 0.3).map((p) => <span key={p.n} className="node" style={{ left: p.left, top: p.top }}><i />{p.n}</span>)}
        {place(outer, 46).map((p) => <span key={p.n} className="node" style={{ left: p.left, top: p.top }}><i />{p.n}</span>)}
      </div>
    </div>
  )
}
