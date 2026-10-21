import { useEffect, useState } from 'react'
import { Play, Pause, Check, Clock, SkipForward, Zap, Terminal, CheckCircle2, AlertTriangle, RotateCcw } from 'lucide-react'
import { useI18n } from './i18n'

// Demo script runner: automated walkthrough with step-by-step actions, screenshots, and timing.
// Runs the 9-step demo script from 08_PROJECT_GUIDE.md programmatically.

const STEPS = [
  { id: 'overview', title: { en: 'Overview', ar: 'نظرة عامة' }, desc: { en: 'Switch languages, click service ring', ar: 'بدّل اللغات، اضغط حلقة الخدمات' }, target: 'home', action: 'wait', duration: 30 },
  { id: 'citizen', title: { en: 'Citizen sign-in', ar: 'دخول المتعامل' }, desc: { en: 'Sign in with National ID, see dashboard', ar: 'ادخل بالهوية الوطنية، راجع اللوحة' }, target: 'dashboard', action: 'customerSignIn', duration: 90 },
  { id: 'pay', title: { en: 'Pay all fines', ar: 'ادفع كل الغرامات' }, desc: { en: 'Pay all → mock checkout → receipt → simulate retry', ar: 'ادفع الكل ← صفحة الدفع ← الإيصال ← محاكاة إعادة المحاولة' }, target: 'dashboard', action: 'payAll', duration: 60 },
  { id: 'control', title: { en: 'Control room', ar: 'غرفة التحكم' }, desc: { en: 'Record violations, watch points → suspension at 24', ar: 'سجّل مخالفات، راقب النقاط → الإيقاف عند 24' }, target: 'control', action: 'recordViolations', duration: 90 },
  { id: 'contact', title: { en: 'Contact & alerts', ar: 'التواصل والتنبيهات' }, desc: { en: 'Change mobile → OTP step-up (scan QR once)', ar: 'غيّر الجوال ← رمز لمرة واحدة (امسح QR مرة)' }, target: 'settings', action: 'stepUp', duration: 45 },
  { id: 'modules', title: { en: 'Module tour', ar: 'جولة الوحدات' }, desc: { en: 'Vehicles (UAT-3), rental (UAT-2), road permit, cross-border (UAT-5), search', ar: 'مركبات (UAT-3)، تأجير (UAT-2)، تصريح طريق، حدود (UAT-5)، بحث' }, target: 'modules', action: 'moduleTour', duration: 90 },
  { id: 'analytics', title: { en: 'Analytics & assistant', ar: 'التحليلات والمساعد' }, desc: { en: 'Charts, live audit trail, verify hash chain, assistant explains fines', ar: 'رسوم بيانية، تدقيق مباشر، تحقّق من السلسلة، المساعد يشرح المخالفات' }, target: 'analytics', action: 'analytics', duration: 45 },
  { id: 'platform', title: { en: 'Platform & API', ar: 'المنصة والواجهات' }, desc: { en: 'Architecture map, roadmap 0-14, requirement status, API explorer', ar: 'خريطة البنية، خارطة الطريق، حالة المتطلبات، مستكشف الواجهات' }, target: 'platform', action: 'platform', duration: 30 },
]

type Step = typeof STEPS[0]

export function DemoScript({ go, lang, customerSignIn }: { go: (r: string) => void; lang: string; customerSignIn: () => void }) {
  const { t } = useI18n()
  const ar = lang === 'ar'
  const [running, setRunning] = useState(false)
  const [idx, setIdx] = useState(0)
  const [status, setStatus] = useState<'idle' | 'running' | 'paused' | 'done' | 'error'>('idle')
  const [stepStart, setStepStart] = useState<number>(0)
  const [results, setResults] = useState<Record<string, { ok: boolean; note?: string }>>({})

  const step = STEPS[idx]

  useEffect(() => {
    if (!running || status !== 'running') return
    const elapsed = (Date.now() - stepStart) / 1000
    if (elapsed >= step.duration) {
      next()
    }
  }, [running, status, idx, stepStart, step?.duration])

  const start = () => {
    setRunning(true)
    setStatus('running')
    setIdx(0)
    setResults({})
    setStepStart(Date.now())
    go(STEPS[0].target)
  }

  const pause = () => { setStatus('paused'); setRunning(false) }
  const resume = () => { setStatus('running'); setRunning(true); setStepStart(Date.now()) }

  const next = async () => {
    if (idx >= STEPS.length - 1) {
      setStatus('done')
      setRunning(false)
      return
    }
    setResults((r) => ({ ...r, [step.id]: { ok: true, note: t.retry } }))
    setIdx((i) => i + 1)
    setStepStart(Date.now())
    go(STEPS[idx + 1].target)
  }

  const reset = () => { setRunning(false); setStatus('idle'); setIdx(0); setResults({}) }

  const getActionBtn = () => {
    if (status === 'idle') return <button className="btn primary lg" onClick={start}><Play size={18} /> {ar ? 'بدء العرض' : 'Start demo'}</button>
    if (status === 'running') return <button className="btn danger" onClick={pause}><Pause size={18} /> {ar ? 'إيقاف مؤقت' : 'Pause'}</button>
    if (status === 'paused') return <button className="btn primary" onClick={resume}><Play size={18} /> {ar ? 'استئناف' : 'Resume'}</button>
    if (status === 'done') return <button className="btn ghost" onClick={reset}><RotateCcw size={18} /> {ar ? 'إعادة' : 'Restart'}</button>
    return <button className="btn ghost" onClick={reset}><AlertTriangle size={18} /> {ar ? 'خطأ — إعادة' : 'Error — restart'}</button>
  }

  return (
    <section className="card demo-script page-enter" aria-label={ar ? 'برنامج العرض التوضيحي' : 'Demo script runner'}>
      <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '0 0 14px' }}>
        <Zap size={18} /> {ar ? 'برنامج العرض التوضيحي' : 'Demo script runner'}
        <span className={`pill ${status === 'running' ? 'warn' : status === 'done' ? 'ok' : 'info'}`}>
          {status === 'idle' ? (ar ? 'جاهز' : 'Ready') : status === 'running' ? (ar ? 'قيد التشغيل' : 'Running') : status === 'paused' ? (ar ? 'موقوف' : 'Paused') : status === 'done' ? (ar ? 'مكتمل' : 'Done') : (ar ? 'خطأ' : 'Error')}
        </span>
      </h3>

      <div className="demo-script-steps">
        {STEPS.map((s, i) => {
          const res = results[s.id]
          const isCurrent = i === idx && status === 'running'
          const isDone = i < idx || (i === idx && status === 'done')
          return (
            <div key={s.id} className={`demo-step ${isCurrent ? 'current' : ''} ${isDone ? 'done' : ''}`} style={{ animationDelay: `${i * 0.05}s` }}>
              <div className="demo-step-main">
                <div className="demo-step-icon">
                  {isDone ? <CheckCircle2 size={18} className="ok" /> : isCurrent ? <RotateCcw size={18} className="spin" /> : <Clock size={18} />}
                </div>
                <div className="demo-step-info">
                  <div className="demo-step-title">{ar ? s.title.ar : s.title.en}</div>
                  <div className="demo-step-desc">{ar ? s.desc.ar : s.desc.en}</div>
                </div>
                <div className="demo-step-meta">
                  {res?.ok && <Check size={14} className="ok" />}
                  {res?.note && <span className="muted mono" style={{ fontSize: 11 }}>{res.note}</span>}
                  <span className="muted mono" style={{ fontSize: 11 }}>{s.duration}s</span>
                </div>
              </div>
              <div className="demo-step-bar">
                <div className="demo-step-fill" style={{ width: isCurrent ? `${((Date.now() - stepStart) / 1000 / step.duration) * 100}%` : isDone ? '100%' : '0%' }} />
              </div>
            </div>
          )
        })}
      </div>

      <div style={{ display: 'flex', gap: 10, marginTop: 16, justifyContent: 'center' }}>
        {getActionBtn()}
        {status === 'running' && <button className="btn" onClick={next}><SkipForward size={16} /> {ar ? 'التالي' : 'Next'}</button>}
      </div>

      {status === 'done' && (
        <div className="banner info" style={{ marginTop: 16 }}>
          <div className="grow"><b>{ar ? 'العرض التوضيحي مكتمل!' : 'Demo complete!'}</b> <span className="muted">{ar ? 'جميع الخطوات تمت بنجاح.' : 'All steps completed successfully.'}</span></div>
        </div>
      )}
    </section>
  )
}