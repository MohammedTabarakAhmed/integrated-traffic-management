import { useCallback, useEffect, useState } from 'react'
import { Bell, BellRing, Bot, CalendarDays, Boxes, Braces, ClipboardCheck, Gauge as GaugeIcon, Globe, Home as HomeIcon, LayoutDashboard, LogOut, Radar, Server, ShieldCheck } from 'lucide-react'
import { ApiError, SignedOut, call } from './api'
import { completeRedirect, load, signInCustomer, signOut } from './auth'
import { useI18n } from './i18n'
import ControlRoom from './pages/ControlRoom'
import Dashboard from './pages/Dashboard'
import Analytics from './pages/Analytics'
import Assistant from './pages/Assistant'
import Home from './pages/Home'
import Modules from './pages/Modules'
import Platform from './pages/Platform'
import Qa from './pages/Qa'
import Receipt from './pages/Receipt'
import Services from './pages/Services'
import Settings from './pages/Settings'

type Route = 'home' | 'dashboard' | 'services' | 'settings' | 'receipt' | 'qa' | 'control' | 'modules' | 'analytics' | 'assistant' | 'platform'

const routeOf = (): Route => ((location.hash.replace('#/', '').split('?')[0] || 'home') as Route)

// Unread in-app messages of the signed-in citizen, refreshed every 15 s; opens the dashboard inbox.
function InboxBell({ go }: { go: (r: string) => void }) {
  const [n, setN] = useState(0)
  useEffect(() => {
    const tick = () => call<{ items: { readAt?: string | null }[] }>('customer', 'GET', '/v1/me/inbox')
      .then((r) => setN(r.items.filter((m) => !m.readAt).length)).catch(() => {})
    tick()
    const id = setInterval(tick, 15000)
    return () => clearInterval(id)
  }, [])
  return (
    <button className="btn bell" onClick={() => go('dashboard')} aria-label={`${n} unread messages`}>
      <Bell size={16} />{n > 0 && <span className="bell-n">{n > 99 ? '99+' : n}</span>}
    </button>
  )
}

export default function App() {
  const { t, lang, toggle } = useI18n()
  const [route, setRoute] = useState<Route>(routeOf)
  const [customer, setCustomer] = useState(() => load('customer'))
  const [toast, setToast] = useState<{ text: string; bad?: boolean } | null>(null)

  useEffect(() => {
    completeRedirect()
      .then((back) => {
        if (back !== null) {
          setCustomer(load('customer'))
          setRoute(routeOf())
        }
      })
      .catch((e) => setToast({ text: String(e.message ?? e), bad: true }))
    const onHash = () => setRoute(routeOf())
    addEventListener('hashchange', onHash)
    return () => removeEventListener('hashchange', onHash)
  }, [])

  useEffect(() => {
    if (!toast) return
    const id = setTimeout(() => setToast(null), 5000)
    return () => clearTimeout(id)
  }, [toast])

  const go = (r: string) => { location.hash = `#/${r}` }
  const customerSignIn = () => signInCustomer({ returnTo: '#/dashboard', lang })

  const onError = useCallback((e: unknown) => {
    if (e instanceof SignedOut) { setCustomer(null); return }
    if (e instanceof ApiError && e.status === 404) { setToast({ text: t.noFile, bad: true }); return }
    setToast({ text: e instanceof Error ? e.message : String(e), bad: true })
  }, [t])

  const nav: { sec: string; items: { r: Route; label: string; icon: React.ReactNode; soon?: boolean }[] }[] = [
    { sec: t.sections.citizen, items: [
      { r: 'home', label: t.nav.home, icon: <HomeIcon size={18} /> },
      { r: 'dashboard', label: t.nav.dashboard, icon: <LayoutDashboard size={18} /> },
      { r: 'services', label: lang === 'ar' ? 'خدماتي' : 'My services', icon: <CalendarDays size={18} /> },
      { r: 'settings', label: t.nav.settings, icon: <BellRing size={18} /> },
      { r: 'assistant', label: t.nav.assistant, icon: <Bot size={18} /> },
    ] },
    { sec: t.sections.staff, items: [
      { r: 'control', label: t.nav.control, icon: <Radar size={18} /> },
      { r: 'modules', label: t.nav.modules, icon: <Boxes size={18} /> },
      { r: 'analytics', label: t.nav.analytics, icon: <GaugeIcon size={18} /> },
    ] },
    { sec: t.sections.system, items: [
      { r: 'platform', label: t.nav.platform, icon: <Server size={18} /> },
      { r: 'qa', label: lang === 'ar' ? 'الجودة والأمان' : 'QA & security', icon: <ClipboardCheck size={18} /> },
    ] },
  ]
  const current = nav.flatMap((s) => s.items).find((i) => i.r === route) ?? (route === 'receipt' ? { label: t.receipt } : undefined)

  let page: React.ReactNode
  if (route === 'home') page = <Home go={go} onCustomer={customerSignIn} />
  else if (route === 'dashboard') {
    page = customer ? <Dashboard onError={onError} notify={(text) => setToast({ text })} /> : (
      <div className="card" style={{ maxWidth: 520, margin: '8vh auto', textAlign: 'center', padding: 36 }}>
        <div className="logo-mark" style={{ margin: '0 auto 18px', width: 56, height: 56 }}><ShieldCheck /></div>
        <h2 style={{ marginTop: 0 }}>{t.signInNational}</h2>
        <p className="muted">{t.tagline}</p>
        <button className="btn primary lg" onClick={customerSignIn}><ShieldCheck size={18} /> {t.signInNational}</button>
      </div>
    )
  } else if (route === 'services') {
    page = customer ? <Services notify={(text, bad) => setToast({ text, bad })} /> : <div className="card empty" style={{ padding: 60 }}><button className="btn primary lg" onClick={() => signInCustomer({ returnTo: '#/services', lang })}><ShieldCheck size={18} /> {t.signInNational}</button></div>
  } else if (route === 'settings') {
    page = customer ? <Settings notify={(text, bad) => setToast({ text, bad })} /> : <div className="card empty" style={{ padding: 60 }}><button className="btn primary lg" onClick={() => signInCustomer({ returnTo: '#/settings', lang })}><ShieldCheck size={18} /> {t.signInNational}</button></div>
  } else if (route === 'control') page = <ControlRoom />
  else if (route === 'modules') page = <Modules />
  else if (route === 'analytics') page = <Analytics />
  else if (route === 'assistant') page = <Assistant go={go} />
  else if (route === 'platform') page = <Platform />
  else if (route === 'qa') page = <Qa go={go} />
  else if (route === 'receipt') page = <Receipt go={go} />
  else page = <div className="card empty" style={{ padding: 60 }}>{t.comingSoon}</div>

  return (
    <div className="shell">
      <aside className="side">
        <div className="logo">
          <div className="logo-mark">IT</div>
          <div><b>{t.brand}</b><small>{t.brandLong}</small></div>
        </div>
        {nav.map((s) => (
          <div key={s.sec}>
            <div className="nav-sec">{s.sec}</div>
            {s.items.map((i) => (
              <button key={i.r} className={`nav-item ${route === i.r ? 'active' : ''}`} onClick={() => go(i.r)} aria-current={route === i.r ? 'page' : undefined}>
                {i.icon}{i.label}{i.soon && <span className="soon">•</span>}
              </button>
            ))}
          </div>
        ))}
        <a className="nav-item" href="/api.html" target="_blank" rel="noreferrer"><Braces size={18} />{lang === 'ar' ? 'مستكشف الواجهات' : 'API explorer'}<span className="soon">↗</span></a>
        <div className="side-foot">
          <span className="pill ok" style={{ alignSelf: 'flex-start' }}><span className="dot live" /> local stack · gateway :9080</span>
        </div>
      </aside>
      <main className="main">
        {route !== 'home' && (
          <div className="topbar">
            <div>
              <h1>{route === 'dashboard' && customer ? `${t.hello}` : current?.label}</h1>
              <div className="sub">{route === 'dashboard' ? t.sections.citizen : current?.label}</div>
            </div>
            <div className="spacer" />
            {customer && <InboxBell go={go} />}
            <button className="btn" onClick={toggle}><Globe size={16} /> {t.language}</button>
            {customer && (route === 'dashboard' || route === 'settings' || route === 'services') && (
              <button className="btn ghost" onClick={() => { signOut('customer'); setCustomer(null) }}><LogOut size={16} /> {t.signOut}</button>
            )}
          </div>
        )}
        {route === 'home' && (
          <div className="topbar"><div className="spacer" /><button className="btn" onClick={toggle}><Globe size={16} /> {t.language}</button></div>
        )}
        {page}
      </main>
      {toast && <div className={`toast ${toast.bad ? 'bad' : ''}`} role="status">{toast.text}</div>}
    </div>
  )
}
