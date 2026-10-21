import { LiveTicker } from './LiveTicker'
import { useCallback, useEffect, useState } from 'react'
import { Bell, BellRing, Bot, Moon, Search, Sun, UserRound, Briefcase, BookOpen, CalendarDays, FileUp, ListChecks, FileBarChart, SlidersHorizontal, Boxes, Braces, ClipboardCheck, Gauge as GaugeIcon, Globe, Home as HomeIcon, LayoutDashboard, LogOut, Radar, Server, ShieldCheck, LogIn, User, CheckCheck, LogIn as LogInIcon } from 'lucide-react'
import { ApiError, SignedOut, call } from './api'
import { completeRedirect, load, signInCustomer, signInStaff, signInChecker, signOut } from './auth'
import { PageBoundary } from './components'
import Hidden from './Hidden'
import { RequirementsPanel } from './RequirementsPanel'
import { HealthPanel } from './HealthPanel'
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
import Documents from './pages/Documents'
import Requirements from './pages/Requirements'
import Guide from './pages/Guide'
import Qa from './pages/Qa'
import Receipt from './pages/Receipt'
import Reports from './pages/Reports'
import Services from './pages/Services'
import Settings from './pages/Settings'

type Route = 'requirements' | 'guide' | 'home' | 'dashboard' | 'profile' | 'documents' | 'services' | 'backoffice' | 'admin' | 'reports' | 'settings' | 'receipt' | 'qa' | 'control' | 'modules' | 'analytics' | 'assistant' | 'platform'

const routeOf = (): Route => ((location.hash.replace('#/', '').split('?')[0] || 'home') as Route)

// Role-based navigation: each role sees only its own pages.
const NAV_BY_ROLE: Record<string, { sec: string; items: { r: Route; label: string; icon: React.ReactNode }[] }[]> = {
  customer: [
    { sec: 'Citizen portal', items: [
      { r: 'home', label: 'Overview', icon: <HomeIcon size={18} /> },
      { r: 'guide', label: 'Service guide', icon: <BookOpen size={18} /> },
      { r: 'dashboard', label: 'My dashboard', icon: <LayoutDashboard size={18} /> },
      { r: 'profile', label: 'My profile', icon: <UserRound size={18} /> },
      { r: 'documents', label: 'My documents', icon: <FileUp size={18} /> },
      { r: 'services', label: 'My services', icon: <CalendarDays size={18} /> },
      { r: 'settings', label: 'Contact & alerts', icon: <BellRing size={18} /> },
      { r: 'assistant', label: 'Assistant', icon: <Bot size={18} /> },
    ] },
  ],
  staff: [
    { sec: 'Staff console', items: [
      { r: 'control', label: 'Control room', icon: <Radar size={18} /> },
      { r: 'backoffice', label: 'Back office', icon: <Briefcase size={18} /> },
      { r: 'modules', label: 'Modules', icon: <Boxes size={18} /> },
      { r: 'analytics', label: 'Analytics', icon: <GaugeIcon size={18} /> },
      { r: 'reports', label: 'Reports', icon: <FileBarChart size={18} /> },
    ] },
    { sec: 'System', items: [
      { r: 'admin', label: 'Administration', icon: <SlidersHorizontal size={18} /> },
      { r: 'platform', label: 'Platform', icon: <Server size={18} /> },
      { r: 'requirements', label: 'Requirements', icon: <ListChecks size={18} /> },
      { r: 'qa', label: 'QA & security', icon: <ClipboardCheck size={18} /> },
    ] },
  ],
  checker: [
    { sec: 'Staff console', items: [
      { r: 'backoffice', label: 'Back office', icon: <Briefcase size={18} /> },
      { r: 'modules', label: 'Modules', icon: <Boxes size={18} /> },
      { r: 'analytics', label: 'Analytics', icon: <GaugeIcon size={18} /> },
      { r: 'reports', label: 'Reports', icon: <FileBarChart size={18} /> },
    ] },
    { sec: 'System', items: [
      { r: 'admin', label: 'Administration', icon: <SlidersHorizontal size={18} /> },
      { r: 'platform', label: 'Platform', icon: <Server size={18} /> },
      { r: 'requirements', label: 'Requirements', icon: <ListChecks size={18} /> },
      { r: 'qa', label: 'QA & security', icon: <ClipboardCheck size={18} /> },
    ] },
  ],
}

// Allowed routes per role (for RBAC guard)
const ALLOWED_ROUTES: Record<string, Route[]> = {
  customer: ['home', 'guide', 'dashboard', 'profile', 'documents', 'services', 'settings', 'assistant', 'receipt'],
  staff: ['home', 'control', 'backoffice', 'modules', 'analytics', 'reports', 'admin', 'platform', 'requirements', 'qa', 'guide', 'dashboard', 'profile', 'documents', 'services', 'settings', 'assistant', 'receipt'],
  checker: ['home', 'backoffice', 'modules', 'analytics', 'reports', 'admin', 'platform', 'requirements', 'qa', 'guide', 'dashboard', 'profile', 'documents', 'services', 'settings', 'assistant', 'receipt'],
}

// Check if current role has access to a route
function canAccessRoute(role: string | null, route: Route): boolean {
  if (!role) return route === 'home'
  const allowed = ALLOWED_ROUTES[role] ?? []
  return allowed.includes(route)
}

const ABOUT: Record<string, [string, string]> = {
  profile: ['What your traffic file says about you, and how to change it.', 'ما يحتويه ملفك المروري عنك وكيفية تغييره.'],
  documents: ['Upload your Emirates ID, licence or photo; each file is checked and scanned.', 'ارفع هويتك أو رخصتك أو صورتك؛ يُفحص كل ملف.'],
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
  guide: ['Before you start: each service’s fee, documents, time and who can use it; verify a certificate.', 'قبل أن تبدأ: رسوم كل خدمة ومستنداتها ومدتها ومن يحق له؛ وتحقق من شهادة.'],
  requirements: ['All 438 requirements: what is verified, in progress or still to do, and where to try each.', 'كل المتطلبات الـ438: ما تحقق وما هو قيد التنفيذ وما تبقى، وأين تجرّب كلًا منها.'],
  qa: ['Checks you can run yourself, with the result we expect for each.', 'فحوص يمكنك تشغيلها بنفسك مع النتيجة المتوقعة لكل منها.'],
}

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
    <button className="btn" data-theme-toggle onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))} aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'} title={theme === 'dark' ? 'Light theme' : 'Dark theme'}>
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

// Role sign-in card shown on the home page.
function RoleCard({ kind, label, sub, icon, acc, onClick }: { kind: string; label: string; sub: string; icon: React.ReactNode; acc: string; onClick: () => void }) {
  return (
    <button className="btn primary lg role-card" onClick={onClick} style={{ width: '100%', textAlign: 'start', justifyContent: 'flex-start' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'rgba(34, 211, 238, 0.12)', color: 'var(--cyan)', border: '1px solid rgba(34, 211, 238, 0.25)' }}>{icon}</div>
        <div>
          <div style={{ fontWeight: 700, fontSize: 16, color: 'var(--text)' }}>{label}</div>
          <div style={{ color: 'var(--muted)', fontSize: 13 }}>{sub}</div>
        </div>
      </div>
      <div style={{ marginInlineStart: 'auto', fontSize: 13, color: 'var(--muted)' }}>{acc}</div>
    </button>
  )
}

export default function App() {
  const { t, lang, toggle } = useI18n()
  const [route, setRoute] = useState<Route>(routeOf)
  const [customer, setCustomer] = useState(() => load('customer'))
  const [staff, setStaff] = useState(() => load('staff'))
  const [checker, setChecker] = useState(() => load('checker'))
  const [kind, setKind] = useState<'customer' | 'staff' | 'checker' | null>(() => {
    if (load('customer')) return 'customer'
    if (load('staff')) return 'staff'
    if (load('checker')) return 'checker'
    return null
  })
  const [toast, setToast] = useState<{ text: string; bad?: boolean } | null>(null)
  const [logoClicks, setLogoClicks] = useState(0)
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
          setKind('customer')
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

  // Staff sign-in helper with preset credentials shown in the role card.
  const staffSignIn = (username: string, password: string) => {
    signInStaff(username, password)
      .then(() => { setStaff(load('staff')); setKind('staff'); setRoute(routeOf()) })
      .catch((e) => setToast({ text: String(e.message ?? e), bad: true }))
  }

  // Checker sign-in helper
  const checkerSignIn = (username: string, password: string) => {
    signInChecker(username, password)
      .then(() => { setChecker(load('checker')); setKind('checker'); setRoute(routeOf()) })
      .catch((e) => setToast({ text: String(e.message ?? e), bad: true }))
  }

  const onError = useCallback((e: unknown) => {
    if (e instanceof SignedOut) { setCustomer(null); setKind(null); setRoute('home'); return }
    if (e instanceof ApiError && e.status === 404) { setToast({ text: t.noFile, bad: true }); return }
    setToast({ text: e instanceof Error ? e.message : String(e), bad: true })
  }, [t])

  const nav = kind ? NAV_BY_ROLE[kind] : []
  const current = nav.flatMap((s) => s.items).find((i) => i.r === route) ?? (route === 'receipt' ? { label: t.receipt } : undefined)

  // RBAC guard: redirect unauthorized access to home
  if (!canAccessRoute(kind, route)) {
    if (kind) {
      // Logged in but not authorized for this route
      setRoute('home')
      location.hash = '#/home'
    } else {
      // Not logged in - will show sign-in prompt in main content
    }
  }

  const signInHeadline = kind ? `Sign in as ${kind}` : 'Welcome to ITMS'
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
  } else if (route === 'documents') {
    page = customer ? <Documents notify={(text, bad) => setToast({ text, bad })} /> : <div className="card empty" style={{ padding: 60 }}><button className="btn primary lg" onClick={() => signInCustomer({ returnTo: '#/documents', lang })}><ShieldCheck size={18} /> {t.signInNational}</button></div>
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
  else if (route === 'requirements') page = <Requirements go={go} />
  else if (route === 'guide') page = <Guide />
  else if (route === 'qa') page = <Qa go={go} />
  else if (route === 'receipt') page = <Receipt go={go} />
  else page = <div className="card empty" style={{ padding: 60 }}>{t.comingSoon}</div>

  const signedIn = kind !== null

  // RBAC guard: check if current role can access this route
  const hasAccess = canAccessRoute(kind, route)
  const [showUnauthorized, setShowUnauthorized] = useState(false)

  useEffect(() => {
    if (signedIn && !hasAccess) {
      setShowUnauthorized(true)
      const timer = setTimeout(() => {
        setRoute('home')
        location.hash = '#/home'
        setShowUnauthorized(false)
      }, 2000)
      return () => clearTimeout(timer)
    }
  }, [signedIn, hasAccess, route])

  const handleSignOut = () => {
    if (kind === 'customer') signOut('customer')
    else if (kind === 'staff') signOut('staff')
    else if (kind === 'checker') signOut('checker')
    setKind(null)
    setCustomer(null)
    setStaff(null)
    setChecker(null)
    setRoute('home')
  }

  return (
    <div className={`shell ${signedIn ? '' : 'no-sidebar'}`}>
      <div className="uae-ribbon" aria-hidden="true" />
      <Hidden go={go} notify={(text) => setToast({ text })} logoClicks={logoClicks} />
      {signedIn && (
        <aside className="side">
          <div className="logo">
            <div className="logo-mark flag" role="img" aria-label="UAE flag" onClick={() => setLogoClicks((n) => n + 1)}>IT</div>
            <div><b>{t.brand}</b><small>{t.brandLong}</small></div>
          </div>
          {nav.map((s) => (
            <div key={s.sec}>
              <div className="nav-sec">{s.sec}</div>
              {s.items.map((i) => (
                <button key={i.r} className={`nav-item ${route === i.r ? 'active' : ''}`} onClick={() => go(i.r)} aria-current={route === i.r ? 'page' : undefined}>
                  {i.icon}{i.label}
                </button>
              ))}
            </div>
          ))}
          <a className="nav-item" href="/api.html" target="_blank" rel="noreferrer"><Braces size={18} />{lang === 'ar' ? 'مستكشف الواجهات' : 'API explorer'}<span className="soon">↗</span></a>
          <button className="nav-item" onClick={handleSignOut} style={{ marginTop: 12, color: 'var(--red)' }}>
            <LogOut size={16} /> {t.signOut}
          </button>
          <RequirementsPanel go={go} lang={lang} />
          <HealthPanel lang={lang} />
          <div className="side-foot">
            <span className="pill ok" style={{ alignSelf: 'flex-start' }}><span className="dot live" /> local stack · gateway :9080</span>
            <small className="muted" style={{ fontSize: 11.5 }}>{lang === 'ar' ? 'اضغط ? للاختصارات' : 'Press ? for shortcuts'}</small>
          </div>
        </aside>
      )}
      <main className="main">
        {!signedIn && route !== 'home' && (
          <div className="card" style={{ maxWidth: 520, margin: '8vh auto', textAlign: 'center', padding: 40 }}>
            <ShieldCheck size={48} style={{ marginBottom: 18 }} />
            <h2 style={{ margin: '0 0 8px 0' }}>{signInHeadline}</h2>
            <p className="muted">{t.tagline}</p>
            <button className="btn primary lg" onClick={() => go('home')} style={{ marginTop: 18 }}><LogIn size={18} /> {lang === 'ar' ? 'العودة إلى البداية' : 'Back to start'}</button>
          </div>
        )}
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
            {signedIn && (
              <button className="btn ghost" onClick={handleSignOut}><LogOut size={16} /> {t.signOut}</button>
            )}
          </div>
        )}
        {route === 'home' && (
          <div className="topbar"><div className="spacer" /><ThemeToggle /><button className="btn" onClick={toggle}><Globe size={16} /> {t.language}</button></div>
        )}
        <PageBoundary key={route} ar={lang === 'ar'}>{page}</PageBoundary>
      </main>
      {toast && <div className={`toast ${toast.bad ? 'bad' : ''}`} role="status">{toast.text}</div>}
      {signedIn && <LiveTicker lang={lang} />}
      <div className="ambient-particles" aria-hidden="true">
        {Array.from({ length: 8 }, (_, i) => (
          <i key={i} style={{ left: `${10 + (i * 11.25)}%`, top: `${30 + (i * 7)}%`, animationDelay: `${i * 1.8}s` }} />
        ))}
      </div>
    </div>
  )
}
