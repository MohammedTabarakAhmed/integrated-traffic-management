import { useEffect, useRef, useState } from 'react'
import { BellRing, KeyRound, Mail, Phone, ShieldCheck } from 'lucide-react'
import { ApiError, call } from '../api'
import { load, signInCustomer } from '../auth'
import { Card, Skeleton } from '../components'
import { useI18n } from '../i18n'

type Channel = 'SMS' | 'EMAIL' | 'PUSH' | 'INBOX'
type Recipient = { id: string; language: 'NL' | 'EN'; phone?: string | null; email?: string | null; push: boolean; preferences: Record<string, Channel[]> }
type Draft = { language: 'NL' | 'EN'; phone: string | null; email: string | null; preferences: Record<string, Channel[]> }

const CHANNELS: Channel[] = ['SMS', 'EMAIL', 'INBOX']
const CATEGORIES: [string, string, string][] = [
  ['VIOLATION', 'Violations and fines', 'المخالفات والغرامات'],
  ['PAYMENT', 'Payments and receipts', 'المدفوعات والإيصالات'],
  ['REMINDER', 'Reminders and expiry', 'التذكيرات وانتهاء الصلاحية'],
]
const DRAFT = 'itms.settings.draft'

// Contact details and channel preferences (CC-073). Saving is a profile change: the service asks for a one-time
// password sign-in (step-up, SEC-002); the portal then re-signs in with acr_values=otp and saves the kept draft.
export default function Settings({ notify }: { notify: (text: string, bad?: boolean) => void }) {
  const { t, lang } = useI18n()
  const [r, setR] = useState<Recipient | null>(null)
  const [d, setD] = useState<Draft | null>(null)
  const [busy, setBusy] = useState(false)
  const resumed = useRef(false)
  const otp = load('customer')?.claims.acr === 'otp'

  const save = async (draft: Draft) => {
    setBusy(true)
    try {
      const saved = await call<Recipient>('customer', 'POST', '/v1/me/notification-preferences', draft, lang)
      sessionStorage.removeItem(DRAFT)
      setR(saved)
      setD({ language: saved.language, phone: null, email: null, preferences: saved.preferences })
      notify(t.savedOtp)
    } catch (e) {
      if (e instanceof ApiError && e.stepUpNeeded) {
        sessionStorage.setItem(DRAFT, JSON.stringify(draft))
        notify(t.stepUp)
        setTimeout(() => signInCustomer({ acr: 'otp', returnTo: '#/settings', lang }), 900)
        return
      }
      notify((e as Error).message, true)
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    // Back from the one-time-password sign-in: save what the customer had asked for (its answer fills the page).
    const kept = sessionStorage.getItem(DRAFT)
    if (kept && otp) {
      if (!resumed.current) { resumed.current = true; void save(JSON.parse(kept)) }
      return
    }
    call<Recipient>('customer', 'GET', '/v1/me/notification-preferences', undefined, lang)
      .then((x) => { setR(x); setD({ language: x.language, phone: null, email: null, preferences: x.preferences ?? {} }) })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) {
          setD({ language: lang === 'ar' ? 'NL' : 'EN', phone: null, email: null, preferences: {} })
        } else notify((e as Error).message, true)
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!d) return <div className="grid g-2"><section className="card"><Skeleton /></section></div>

  const toggle = (cat: string, ch: Channel) => {
    const now = d.preferences[cat] ?? []
    const next = now.includes(ch) ? now.filter((c) => c !== ch) : [...now, ch]
    setD({ ...d, preferences: { ...d.preferences, [cat]: next } })
  }

  return (
    <div className="grid g-2">
      <Card title={t.contact} icon={<Phone size={16} />}
        right={otp ? <span className="pill ok"><ShieldCheck size={13} /> OTP</span> : <span className="pill"><KeyRound size={13} /> {t.otpNeeded}</span>}>
        <label className="field"><span><Phone size={12} /> {t.mobile} {r?.phone && <span className="mono">· {r.phone}</span>}</span>
          <input inputMode="tel" placeholder="+9715XXXXXXXX" value={d.phone ?? ''} onChange={(e) => setD({ ...d, phone: e.target.value || null })} /></label>
        <label className="field"><span><Mail size={12} /> {t.email} {r?.email && <span className="mono">· {r.email}</span>}</span>
          <input type="email" placeholder="name@example.ae" value={d.email ?? ''} onChange={(e) => setD({ ...d, email: e.target.value || null })} /></label>
        <label className="field"><span>{t.msgLanguage}</span>
          <select value={d.language} onChange={(e) => setD({ ...d, language: e.target.value as Draft['language'] })}>
            <option value="NL">العربية</option><option value="EN">English</option>
          </select></label>
        <button className="btn primary lg" style={{ width: '100%', justifyContent: 'center' }} disabled={busy} onClick={() => save(d)}>
          <ShieldCheck size={18} /> {t.saveSecure}
        </button>
        <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{t.stepUpNote}</p>
      </Card>
      <Card title={t.channels} icon={<BellRing size={16} />}>
        <div className="list">
          {CATEGORIES.map(([cat, en, ar]) => (
            <div className="row" key={cat}>
              <div className="grow"><div className="title">{lang === 'ar' ? ar : en}</div></div>
              <div style={{ display: 'flex', gap: 6 }}>
                {CHANNELS.map((ch) => {
                  const on = (d.preferences[cat] ?? []).includes(ch)
                  return <button key={ch} className={`chip ${on ? 'on' : ''}`} aria-pressed={on} onClick={() => toggle(cat, ch)}>{ch}</button>
                })}
              </div>
            </div>
          ))}
        </div>
        <p className="muted" style={{ fontSize: 12.5 }}>{t.mandatoryNote}</p>
      </Card>
    </div>
  )
}
