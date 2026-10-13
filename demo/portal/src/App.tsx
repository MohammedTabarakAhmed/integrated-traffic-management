import { useCallback, useEffect, useState } from 'react'
import { Bell, BellRing, Bot, Moon, Search, Sun, UserRound, Briefcase, CalendarDays, FileBarChart, SlidersHorizontal, Boxes, Braces, ClipboardCheck, Gauge as GaugeIcon, Globe, Home as HomeIcon, LayoutDashboard, LogOut, Radar, Server, ShieldCheck } from 'lucide-react'
import { ApiError, SignedOut, call } from './api'
import { completeRedirect, load, signInCustomer, signOut } from './auth'
import { useI18n } from './i18n'
import BackOffice from './pages/BackOffice'
import ControlRoom from './pages/ControlRoom'
import Dashboard from './pages/Dashboard'
import Admin from './pages/Admin'
import Analytics from './pages/Analytics'
import Assistant from './pages/Assistant'
import Home from './pages/Home'
import Modules from './pages/Modules'
import Platform from './pages/Platform'
import Profile from './pages/Profile'
import Qa from './pages/Qa'
import Receipt from './pages/Receipt'
import Reports from './pages/Reports'
import Services from './pages/Services'
import Settings from './pages/Settings'

type Route = 'home' | 'dashboard' | 'profile' | 'services' | 'backoffice' | 'admin' | 'reports' | 'settings' | 'receipt' | 'qa' | 'control' | 'modules' | 'analytics' | 'assistant' | 'platform'

const routeOf = (): Route => ((location.hash.replace('#/', '').split('?')[0] || 'home') as Route)

// One plain sentence under each page title, so a reviewer knows what the page is for.
const ABOUT: Record<string, [string, string]> = {
  profile: ['What your traffic file says about you, and how to change it.', 'ما يحتويه ملفك المروري عنك وكيفية تغييره.'],
  dashboard: ['Your traffic file, fines, black points and messages in one place.', 'ملفك المروري ومخالفاتك ونقاطك ورسائلك في مكان واحد.'],
  services: ['Spread your fines over a few months, or book a visit to a service centre.', 'قسّط غراماتك على عدة أشهر أو احجز موعدًا في مركز الخدمة.'],
  settings: ['How we reach you. Changes ask for a one-time password first.', 'كيف نتواصل معك. التغييرات تتطلب رمزًا لمرة واحدة.'],
  assistant: ['Ask about your fines and points. It reads only your own data.', 'اسأل عن مخالفاتك ونقاطك. يقرأ بياناتك فقط.'],
  receipt: ['Your payment went through. Keep the receipt number for your records.', 'تمت عملية الدفع. احتفظ برقم الإيصال.'],
  control: ['Record fines on the road and watch the driver’s points change live.', 'سجّل المخالفات وتابع تغيّر نقاط السائق مباشرة.'],
  backoffice: ['Decide objections, work through cases and lift holds.', 'البت في الاعتراضات ومعالجة الحالات ورفع الحجوزات.'],
  modules: ['A short, live walk through each part of the system.', 'جولة قصيرة ومباشرة في كل جزء من النظام.'],
  analytics: ['Numbers from the reporting service and the live audit trail.', 'أرقام من خدمة التقارير وسجل التدقيق المباشر.'],
  reports: ['Run a report for any period and download it as a spreadsheet.', 'شغّل تقريرًا لأي فترة ونزّله كجدول بيانات.'],
  admin: ['The settings the system runs on: catalogue, lists, fees and approvals.', 'الإعدادات التي يعمل بها النظام: الدليل والقوائم والرسوم والاعتمادات.'],
  platform: ['What is running right now, and what is built or still to come.', 'ما يعمل الآن، وما بُني وما هو قادم.'],
  qa: ['Checks you can run yourself, with the result we expect for each.', 'فحوص يمكنك تشغيلها بنفسك مع النتيجة المتوقعة لكل منها.'],
}

// Quick jump: Ctrl+K (or Cmd+K) opens a list of pages to filter and open with the keyboard.
function QuickJump({ items, go, lang }: { items: { r: string; label: string; icon: React.ReactNode }[]; go: (r: string) => void; lang: string }) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const [i, setI] = useState(0)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setOpen((o) => !o); setQ(''); setI(0) }
      if (e.key === 'Escape') setOpen(false)
    }
    addEventListener('keydown', onKey)
    return () => removeEventListener('keydown', onKey)
  }, [])
  const list = items.filter((x) => x.label.toLowerCase().includes(q.toLowerCase()))
  const pick = (r: string) => { setOpen(false); go(r) }
  return (
    <>
      <button className="btn quick" onClick={() => { setOpen(true); setQ(''); setI(0) }} aria-label="Quick jump">
        <Search size={15} /><span>{lang === 'ar' ? 'انتقال سريع' : 'Jump to…'}</span><kbd>Ctrl K</kbd>
      </button>
      {open && (
        <div className="modal-back" onClick={() => setOpen(false)}>
          <div className="palette" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
            <input autoFocus value={q} placeholder={lang === 'ar' ? 'اكتب اسم صفحة…' : 'Type a page name…'} aria-label="Page name"
              onChange={(e) => { setQ(e.target.value); setI(0) }}
              onKeyDown={(e) => {
                if (e.key === 'ArrowDown') { e.preventDefault(); setI((n) => Math.min(n + 1, list.length - 1)) }
                if (e.key === 'ArrowUp') { e.preventDefault(); setI((n) => Math.max(n - 1, 0)) }
                if (e.key === 'Enter' && list[i]) pick(list[i].r)
              }} />
            <div className="palette-list">{list.map((x, n) => (
              <button key={x.r} className={n === i ? 'on' : ''} onMouseEnter={() => setI(n)} onClick={() => pick(x.r)}>{x.icon}<span>{x.label}</span></button>))}
              {list.length === 0 && <div className="empty">{lang === 'ar' ? 'لا نتائج' : 'Nothing matches'}</div>}
            </div>
          </div>
        </div>
      )}
    </>
  )
}

function ThemeToggle() {
  const [theme, setTheme] = useState<'dark' | 'light'>(() => { try { return (localStorage.getItem('itms.theme') as 'dark' | 'light') || 'dark' } catch { return 'dark' } })
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try { localStorage.setItem('itms.theme', theme) } catch { /* ignore */ }
  }, [theme])
  return (
    <button className="btn" onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))} aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'} title={theme === 'dark' ? 'Light theme' : 'Dark theme'}>
      {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
    </button>
  )
}

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
  const [firstName, setFirstName] = useState<{ en?: string | null; nl?: string | null } | null>(null)
  // The citizen's first name for the greeting (from their own traffic file).
  useEffect(() => {
    if (!customer) { setFirstName(null); return }
    call<{ identity?: { name?: { givenEn?: string | null; givenNl?: string | null } } | null }>('customer', 'GET', '/v1/me/traffic-file')
      .then((f) => setFirstName({ en: f.identity?.name?.givenEn, nl: f.identity?.name?.givenNl })).catch(() => {})
  }, [customer])

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
      { r: 'profile', label: lang === 'ar' ? 'ملفي الشخصي' : 'My profile', icon: <UserRound size={18} /> },
      { r: 'services', label: lang === 'ar' ? 'خدماتي' : 'My services', icon: <CalendarDays size={18} /> },
      { r: 'settings', label: t.nav.settings, icon: <BellRing size={18} /> },
      { r: 'assistant', label: t.nav.assistant, icon: <Bot size={18} /> },
    ] },
    { sec: t.sections.staff, items: [
      { r: 'control', label: t.nav.control, icon: <Radar size={18} /> },
      { r: 'backoffice', label: lang === 'ar' ? 'المكتب الخلفي' : 'Back office', icon: <Briefcase size={18} /> },
      { r: 'modules', label: t.nav.modules, icon: <Boxes size={18} /> },
      { r: 'analytics', label: t.nav.analytics, icon: <GaugeIcon size={18} /> },
      { r: 'reports', label: lang === 'ar' ? 'التقارير' : 'Reports', icon: <FileBarChart size={18} /> },
    ] },
    { sec: t.sections.system, items: [
      { r: 'admin', label: lang === 'ar' ? 'الإدارة' : 'Administration', icon: <SlidersHorizontal size={18} /> },
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
  } else if (route === 'profile') {
    page = customer ? <Profile go={go} /> : <div className="card empty" style={{ padding: 60 }}><button className="btn primary lg" onClick={() => signInCustomer({ returnTo: '#/profile', lang })}><ShieldCheck size={18} /> {t.signInNational}</button></div>
  } else if (route === 'services') {
    page = customer ? <Services notify={(text, bad) => setToast({ text, bad })} /> : <div className="card empty" style={{ padding: 60 }}><button className="btn primary lg" onClick={() => signInCustomer({ returnTo: '#/services', lang })}><ShieldCheck size={18} /> {t.signInNational}</button></div>
  } else if (route === 'settings') {
    page = customer ? <Settings notify={(text, bad) => setToast({ text, bad })} /> : <div className="card empty" style={{ padding: 60 }}><button className="btn primary lg" onClick={() => signInCustomer({ returnTo: '#/settings', lang })}><ShieldCheck size={18} /> {t.signInNational}</button></div>
  } else if (route === 'control') page = <ControlRoom />
  else if (route === 'modules') page = <Modules />
  else if (route === 'reports') page = <Reports notify={(text, bad) => setToast({ text, bad })} />
  else if (route === 'admin') page = <Admin notify={(text, bad) => setToast({ text, bad })} />
  else if (route === 'backoffice') page = <BackOffice notify={(text, bad) => setToast({ text, bad })} />
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
              <h1>{route === 'dashboard' && customer ? `${t.hello}${(lang === 'ar' ? firstName?.nl : firstName?.en) ? `${lang === 'ar' ? '، ' : ', '}${lang === 'ar' ? firstName?.nl : firstName?.en}` : ''}` : current?.label}</h1>
              <div className="sub">{ABOUT[route]?.[lang === 'ar' ? 1 : 0] ?? ''}</div>
            </div>
            <div className="spacer" />
            <QuickJump items={nav.flatMap((s) => s.items)} go={go} lang={lang} />
            {customer && <InboxBell go={go} />}
            <ThemeToggle />
            <button className="btn" onClick={toggle}><Globe size={16} /> {t.language}</button>
            {customer && ['dashboard', 'settings', 'services', 'profile'].includes(route) && (
              <button className="btn ghost" onClick={() => { signOut('customer'); setCustomer(null) }}><LogOut size={16} /> {t.signOut}</button>
            )}
          </div>
        )}
        {route === 'home' && (
          <div className="topbar"><div className="spacer" /><ThemeToggle /><button className="btn" onClick={toggle}><Globe size={16} /> {t.language}</button></div>
        )}
        {page}
      </main>
      {toast && <div className={`toast ${toast.bad ? 'bad' : ''}`} role="status">{toast.text}</div>}
    </div>
  )
}
