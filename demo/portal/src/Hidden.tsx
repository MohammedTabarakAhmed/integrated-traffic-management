import { useEffect, useRef, useState } from 'react'
import { Keyboard, Terminal, X } from 'lucide-react'
import { recentCalls, type CallLog } from './api'
import { load, type Kind } from './auth'
import { useI18n } from './i18n'

// Hidden features (owner, 2026-10-14), all client-side and read-only:
//  ?            keyboard shortcuts           g then d/c/m/b/a/q/p/o   go to a page
//  t / l        theme / language             ↑↑↓↓←→←→ B A             UAE flag celebration
//  logo × 5     developer panel: who is signed in (token claims, no tokens) and the last API calls
// On UAE National Day (2 December) and Flag Day (3 November) the celebration plays once by itself.

const GO: Record<string, string> = { d: 'dashboard', c: 'control', m: 'modules', b: 'backoffice', a: 'analytics', q: 'qa', p: 'platform', o: 'home', r: 'requirements', s: 'guide' }
const KONAMI = ['ArrowUp', 'ArrowUp', 'ArrowDown', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'ArrowLeft', 'ArrowRight', 'b', 'a']
const FLAG = ['#e4002b', '#00843d', '#ffffff', '#000000']
const typing = (e: KeyboardEvent) => {
  const el = e.target as HTMLElement | null
  return !!el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName))
}

export function Celebration({ onDone }: { onDone: () => void }) {
  useEffect(() => { const id = setTimeout(onDone, 4600); return () => clearTimeout(id) }, [onDone])
  // The flag is cut into slices that rise and fall a little later and a little further away from the pole: a ripple.
  const slices = 24
  return (
    <>
      <div className="confetti" aria-hidden="true">
        {Array.from({ length: 90 }, (_, i) => (
          <i key={i} style={{ left: `${(i * 37) % 100}%`, background: FLAG[i % 4], border: i % 4 === 2 ? '1px solid #ccc' : undefined, animationDelay: `${(i % 15) * 0.07}s` }} />
        ))}
      </div>
      <div className="flag-wave" aria-hidden="true" dir="ltr">
        <div className="pole" />
        <div className="cloth">
          {Array.from({ length: slices }, (_, i) => (
            <span key={i} style={{ backgroundPosition: `${-i * (320 / slices)}px 0`, animationDelay: `${-(slices - i) * 0.07}s`, ['--a' as string]: 1 + i * 0.45 }} />
          ))}
        </div>
      </div>
    </>
  )
}

export default function Hidden({ go, notify, logoClicks }: { go: (r: string) => void; notify: (t: string) => void; logoClicks: number }) {
  const { lang, toggle } = useI18n()
  const ar = lang === 'ar'
  const [sheet, setSheet] = useState(false)
  const [party, setParty] = useState(false)
  const [dev, setDev] = useState(false)
  const [, setTick] = useState(0)
  const seq = useRef<string[]>([])
  const g = useRef(0)

  const celebrate = () => {
    setParty(true)
    notify(ar ? 'كل عام والإمارات بخير — عيد اتحاد سعيد' : 'Happy UAE National Day — عيد اتحاد سعيد')
  }

  // National Day and Flag Day play the celebration once per day by themselves.
  useEffect(() => {
    const d = new Date(); const key = `itms.uae-day.${d.toDateString()}`
    if ((d.getMonth() === 11 && d.getDate() === 2) || (d.getMonth() === 10 && d.getDate() === 3)) {
      try { if (!localStorage.getItem(key)) { localStorage.setItem(key, '1'); celebrate() } } catch { /* storage blocked */ }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => { if (logoClicks >= 5 && logoClicks % 5 === 0) setDev(true) }, [logoClicks])
  useEffect(() => {
    if (!dev) return
    const on = () => setTick((n) => n + 1)
    addEventListener('itms-call', on)
    return () => removeEventListener('itms-call', on)
  }, [dev])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      seq.current = [...seq.current, e.key.length === 1 ? e.key.toLowerCase() : e.key].slice(-KONAMI.length)
      if (KONAMI.every((k, i) => seq.current[i] === k)) { seq.current = []; celebrate(); return }
      if (typing(e) || e.ctrlKey || e.metaKey || e.altKey) return
      if (e.key === 'Escape') { setSheet(false); setDev(false); return }
      if (Date.now() - g.current < 1200 && GO[e.key]) { g.current = 0; go(GO[e.key]); return }
      if (e.key === 'g') { g.current = Date.now(); return }
      if (e.key === '?') setSheet((s) => !s)
      else if (e.key === 't') (document.querySelector('[data-theme-toggle]') as HTMLButtonElement | null)?.click()
      else if (e.key === 'l') toggle()
    }
    addEventListener('keydown', onKey)
    return () => removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [go, toggle, ar])

  const keys: [string, string, string][] = [
    ['Ctrl K', 'Jump to any page', 'الانتقال لأي صفحة'],
    ['?', 'This list', 'هذه القائمة'],
    ['g d', 'My dashboard', 'لوحتي'], ['g c', 'Control room', 'غرفة التحكم'], ['g m', 'Modules', 'الوحدات'],
    ['g b', 'Back office', 'المكتب الخلفي'], ['g a', 'Analytics', 'التحليلات'], ['g r', 'Requirements', 'المتطلبات'],
    ['g s', 'Service guide', 'دليل الخدمات'], ['g q', 'QA & security', 'الجودة والأمان'], ['g o', 'Overview', 'نظرة عامة'],
    ['t', 'Light / dark theme', 'الوضع الفاتح / الداكن'], ['l', 'Arabic / English', 'العربية / الإنجليزية'],
    ['↑↑↓↓←→←→ B A', 'A little surprise', 'مفاجأة صغيرة'],
    ['Logo × 5', 'Developer panel', 'لوحة المطوّر'],
  ]
  const sessions = (['customer', 'staff', 'checker'] as Kind[]).map((k) => [k, load(k)] as const).filter(([, s]) => s)

  return (
    <>
      {party && <Celebration onDone={() => setParty(false)} />}
      {sheet && (
        <div className="modal-back" role="dialog" aria-modal="true" aria-labelledby="kbd-title" onClick={() => setSheet(false)}>
          <div className="card modal kbd-sheet" onClick={(e) => e.stopPropagation()}>
            <button className="btn ghost close" onClick={() => setSheet(false)} aria-label="Close"><X size={18} /></button>
            <h2 id="kbd-title" style={{ marginTop: 0, display: 'flex', gap: 8, alignItems: 'center' }}><Keyboard size={20} /> {ar ? 'اختصارات لوحة المفاتيح' : 'Keyboard shortcuts'}</h2>
            <dl>{keys.map(([k, en, a]) => <div key={k} style={{ display: 'contents' }}><dt><kbd>{k}</kbd></dt><dd style={{ margin: 0 }}>{ar ? a : en}</dd></div>)}</dl>
          </div>
        </div>
      )}
      {dev && (
        <aside className="dev-panel" aria-label="Developer panel">
          <h3><Terminal size={15} /> {ar ? 'لوحة المطوّر' : 'Developer panel'}<button className="btn ghost" style={{ marginInlineStart: 'auto', padding: 4 }} onClick={() => setDev(false)} aria-label="Close"><X size={14} /></button></h3>
          <b>{ar ? 'الجلسات' : 'Sessions'}</b>
          {sessions.length === 0 ? <p className="muted">{ar ? 'لا أحد مسجّل' : 'Nobody signed in'}</p> : (
            <table><tbody>{sessions.map(([k, s]) => (
              <tr key={k}><td>{k}</td><td>{String(s!.claims.preferred_username ?? s!.claims.national_id ?? s!.claims.sub).slice(0, 24)}</td>
                <td>{String(s!.claims.subject_type ?? 'STAFF')}</td><td>acr {String(s!.claims.acr ?? '—')}</td>
                <td>{Math.max(0, Math.round((s!.expiresAt - Date.now()) / 1000))} s</td></tr>))}</tbody></table>
          )}
          <b style={{ display: 'block', marginTop: 10 }}>{ar ? 'آخر طلبات API' : 'Last API calls'}</b>
          <table><tbody>{recentCalls.slice(0, 12).map((c: CallLog) => (
            <tr key={c.at + c.path + c.method}><td>{c.method}</td><td style={{ wordBreak: 'break-all' }}>{c.path}</td>
              <td style={{ color: c.status < 400 ? 'var(--green)' : 'var(--red)' }}>{c.status}</td><td>{c.ms} ms</td></tr>))}</tbody></table>
          <p className="muted" style={{ marginBottom: 0 }}>{ar ? 'لا تُعرض الرموز ولا محتوى الطلبات.' : 'Tokens and request bodies are never shown.'} <kbd>Esc</kbd></p>
        </aside>
      )}
    </>
  )
}
