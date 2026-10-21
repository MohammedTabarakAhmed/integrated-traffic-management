import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Bot, CreditCard, Gavel, ListChecks, OctagonX, Power, Send, ShieldCheck, User, Wrench } from 'lucide-react'
import { call, money, when } from '../api'
import { load, signInCustomer } from '../auth'
import { useI18n } from '../i18n'

// Assistant preview (Increment 13, demo D3.2). It answers from the signed-in customer's own data only, through the same
// /v1/me APIs and permissions as the customer, tagged X-Channel ASSISTANT so each read is audited as the assistant's.
// It never acts: it explains and points to the button the customer would press. The answers are rule-based here; the
// AI agent of Increment 13 replaces the wording behind the same tools, owner, scope and emergency stop.

type V = { number: string; catalogueCode: string; occurredAt: string; location: string; fineMinor: number; blackPoints: number; status: string }
type Acct = { activePoints: number; threshold: number; activeSuspension?: { from: string; until: string; ordinal: number } | null }
type P = { payable: { itemRef: string; total: number; currency: string; status: string }; paused: boolean }
type Msg = { who: 'user' | 'bot'; text: ReactNode; tools?: string[]; action?: { en: string; ar: string; go: string; icon: ReactNode } }

const NAMES: Record<string, [string, string]> = {
  DEMO_SPEED: ['speeding more than 20 km/h over the limit', 'تجاوز السرعة بأكثر من 20 كم/س'],
  DEMO_PHONE: ['using a phone while driving', 'استخدام الهاتف أثناء القيادة'],
  DEMO_PARK: ['parking in a prohibited place', 'الوقوف في مكان غير مخصص'],
  DEMO_REDLIGHT: ['running a red light', 'تجاوز الإشارة الحمراء'],
}
const SUGGEST: [string, string, string][] = [
  ['fines', 'Explain my fines', 'اشرح مخالفاتي'],
  ['points', 'How close am I to a suspension?', 'كم أنا قريب من الإيقاف؟'],
  ['pay', 'How much do I owe and how do I pay?', 'كم عليّ وكيف أدفع؟'],
  ['object', 'Can I object to a fine?', 'هل يمكنني الاعتراض على مخالفة؟'],
  ['suspended', 'Is my licence suspended?', 'هل رخصتي موقوفة؟'],
]
const STOP_KEY = 'itms.assistant.stopped'

function intent(q: string): string {
  const s = q.toLowerCase()
  if (/suspend|موقوف|إيقاف|ايقاف/.test(s)) return 'suspended'
  if (/point|نقط|نقاط|close|قريب/.test(s)) return 'points'
  if (/pay|owe|دفع|أدفع|ادفع|مستحق|كم علي/.test(s)) return 'pay'
  if (/object|appeal|dispute|اعتراض|اعترض/.test(s)) return 'object'
  if (/fine|violation|explain|مخالف|اشرح|غرام/.test(s)) return 'fines'
  return 'help'
}

export default function Assistant({ go }: { go: (r: string) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const customer = load('customer')
  const [msgs, setMsgs] = useState<Msg[]>([])
  const [q, setQ] = useState('')
  const [thinking, setThinking] = useState(false)
  const [stopped, setStopped] = useState(() => { try { return localStorage.getItem(STOP_KEY) === '1' } catch { return false } })
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [msgs, thinking])
  useEffect(() => { try { localStorage.setItem(STOP_KEY, stopped ? '1' : '0') } catch { /* ignore */ } }, [stopped])

  const tool = <T,>(path: string) => call<T>('customer', 'GET', path, undefined, lang, 'ASSISTANT')

  async function answer(kind: string): Promise<Msg> {
    const n = (code: string) => (NAMES[code] ?? [code, code])[ar ? 1 : 0]
    if (kind === 'fines') {
      const v = (await tool<{ items: V[] }>('/v1/me/violations?status=OPEN')).items
      if (!v.length) return { who: 'bot', tools: ['GET /v1/me/violations'], text: ar ? 'لا توجد لديك مخالفات مستحقة. أحسنت!' : 'You have no open fines. Well done!' }
      return { who: 'bot', tools: ['GET /v1/me/violations'], action: { en: 'Open my dashboard', ar: 'افتح لوحتي', go: 'dashboard', icon: <CreditCard size={15} /> }, text: (
        <>
          <p>{ar ? `لديك ${v.length} مخالفات مستحقة:` : `You have ${v.length} open fine${v.length > 1 ? 's' : ''}:`}</p>
          <ul>{v.slice(0, 6).map((x) => (
            <li key={x.number}><b>{n(x.catalogueCode)}</b> — {x.location}, {when(x.occurredAt, lang)}. {money(x.fineMinor, 'AED', lang)}
              {x.blackPoints > 0 && (ar ? ` و${x.blackPoints} نقاط سوداء` : ` and ${x.blackPoints} black points`)}.</li>))}</ul>
        </>) }
    }
    if (kind === 'points' || kind === 'suspended') {
      const a = await tool<Acct>('/v1/me/black-point-account')
      const left = Math.max(0, a.threshold - a.activePoints)
      if (a.activeSuspension) return { who: 'bot', tools: ['GET /v1/me/black-point-account'], text: ar
        ? `${kind === 'suspended' ? 'نعم. ' : 'تجاوزت الحد بالفعل: '}رخصتك موقوفة (الإيقاف رقم ${a.activeSuspension.ordinal}) من ${a.activeSuspension.from} حتى ${a.activeSuspension.until}، لأن النقاط بلغت ${a.threshold}. خلال الإيقاف تُحظر خدمات الترخيص، ويبدأ عدّ النقاط من جديد.`
        : `${kind === 'suspended' ? 'Yes. Your' : 'You have already reached the limit: your'} licence is suspended (suspension #${a.activeSuspension.ordinal}) from ${a.activeSuspension.from} until ${a.activeSuspension.until}, because your points reached ${a.threshold}. Licensing services are blocked while it lasts, and your points count starts again.` }
      if (kind === 'suspended') return { who: 'bot', tools: ['GET /v1/me/black-point-account'], text: ar ? `لا، رخصتك ليست موقوفة. لديك ${a.activePoints} من ${a.threshold} نقطة.` : `No, your licence is not suspended. You have ${a.activePoints} of ${a.threshold} points.` }
      return { who: 'bot', tools: ['GET /v1/me/black-point-account'], text: ar
        ? `لديك ${a.activePoints} من ${a.threshold} نقطة سوداء. يتبقى ${left} نقطة قبل الإيقاف التلقائي. أول إيقاف 3 أشهر، ثم 6، ثم 12 شهرًا. تنتهي كل نقطة بعد سنة من تسجيلها.`
        : `You have ${a.activePoints} of ${a.threshold} black points: ${left} more before an automatic suspension. The first suspension is 3 months, then 6, then 12. Each point expires a year after it was recorded.` }
    }
    if (kind === 'pay') {
      const p = (await tool<{ items: P[] }>('/v1/me/payables')).items.filter((x) => x.payable.status === 'OPEN')
      const due = p.filter((x) => !x.paused)
      const total = due.reduce((s, x) => s + x.payable.total, 0)
      return { who: 'bot', tools: ['GET /v1/me/payables'], action: due.length ? { en: 'Pay all fines', ar: 'ادفع كل الغرامات', go: 'dashboard', icon: <CreditCard size={15} /> } : undefined, text: ar
        ? `عليك ${money(total, due[0]?.payable.currency ?? 'AED', lang)} عن ${due.length} بنود${p.length > due.length ? `، و${p.length - due.length} معلّقة بسبب اعتراض` : ''}. يمكنك دفعها كلها في سلة واحدة من لوحتك؛ أنا لا أدفع نيابة عنك.`
        : `You owe ${money(total, due[0]?.payable.currency ?? 'AED', lang)} for ${due.length} item${due.length === 1 ? '' : 's'}${p.length > due.length ? `; ${p.length - due.length} more are paused by an objection` : ''}. You can pay them all in one basket from your dashboard; I never pay on your behalf.` }
    }
    if (kind === 'object') {
      return { who: 'bot', tools: [], action: { en: 'Go to my violations', ar: 'اذهب إلى مخالفاتي', go: 'dashboard', icon: <Gavel size={15} /> }, text: ar
        ? 'نعم، يمكنك الاعتراض على مخالفة مستحقة خلال مهلة الاعتراض: اختر «اعتراض» بجانبها واذكر السبب. تُعلَّق الغرامة حتى يُبتّ في اعتراضك، ولا تُحتسب نقاطها قبل ذلك.'
        : 'Yes. You can object to an open fine within the objection window: press "Object" next to it and give your reason. The fine is paused until your objection is decided, and its points do not count before then.' }
    }
    return { who: 'bot', tools: [], text: ar ? 'أستطيع شرح مخالفاتك ونقاطك ومستحقاتك وكيفية الاعتراض. جرّب أحد الأسئلة المقترحة.' : 'I can explain your fines, points, what you owe and how to object. Try one of the suggested questions.' }
  }

  const ask = async (text: string, kind = intent(text)) => {
    if (!text.trim() || stopped) return
    setQ('')
    setMsgs((m) => [...m, { who: 'user', text }])
    setThinking(true)
    try {
      const [a] = await Promise.all([answer(kind), new Promise((r) => setTimeout(r, 700))])
      setMsgs((m) => [...m, a])
    } catch (e) {
      setMsgs((m) => [...m, { who: 'bot', text: (e as Error).message }])
    } finally {
      setThinking(false)
    }
  }

  if (!customer) return (
    <div className="card empty" style={{ padding: 60 }}>
      <button className="btn primary lg" onClick={() => signInCustomer({ returnTo: '#/assistant', lang })}><ShieldCheck size={18} /> {ar ? 'الدخول بالهوية الرقمية الوطنية' : 'Sign in with National Digital ID'}</button>
    </div>
  )

  return (
    <div className="assistant">
      <section className="card chat">
        <div className="chat-head">
          <div className={`bot-avatar ${stopped ? 'off' : ''}`}><Bot size={22} /></div>
          <div className="grow">
            <b>{ar ? 'مساعد ITMS' : 'ITMS assistant'}</b>
            <div className="muted" style={{ fontSize: 12.5 }}>{stopped ? (ar ? 'متوقف بأمر الإيقاف الطارئ' : 'Stopped by the emergency switch') : (ar ? 'يقرأ بياناتك فقط، بصلاحياتك أنت' : 'Reads only your data, with your permissions')}</div>
          </div>
          <button className={`btn ${stopped ? 'primary' : 'danger'}`} onClick={() => setStopped((s) => !s)} aria-pressed={stopped}>
            {stopped ? <Power size={16} /> : <OctagonX size={16} />} {stopped ? (ar ? 'إعادة التشغيل' : 'Resume') : (ar ? 'إيقاف طارئ' : 'Emergency stop')}
          </button>
        </div>
        <div className="chat-body">
          {msgs.length === 0 && (
            <div className="bubble bot"><p>{ar ? 'مرحبًا! أنا مساعدك المروري. اسألني عن مخالفاتك أو نقاطك أو مستحقاتك.' : 'Hello! I am your traffic assistant. Ask me about your fines, points or what you owe.'}</p></div>
          )}
          {msgs.map((m, i) => (
            <div key={i} className={`msg ${m.who}`}>
              <div className="who-ico">{m.who === 'bot' ? <Bot size={15} /> : <User size={15} />}</div>
              <div className={`bubble ${m.who}`}>
                {typeof m.text === 'string' ? <p>{m.text}</p> : m.text}
                {m.action && <button className="btn" style={{ marginTop: 8 }} onClick={() => go(m.action!.go)}>{m.action.icon} {ar ? m.action.ar : m.action.en}</button>}
                {m.tools && m.tools.length > 0 && <div className="tools">{m.tools.map((t) => <span key={t} className="mono"><Wrench size={11} /> {t} · X-Channel: ASSISTANT</span>)}</div>}
              </div>
            </div>
          ))}
          {thinking && <div className="msg bot"><div className="who-ico"><Bot size={15} /></div><div className="bubble bot typing"><i /><i /><i /></div></div>}
          <div ref={end} />
        </div>
        <div className="chips">
          {SUGGEST.map(([k, en, a]) => <button key={k} className="chip" disabled={stopped || thinking} onClick={() => ask(ar ? a : en, k)}>{ar ? a : en}</button>)}
        </div>
        <form className="chat-input" onSubmit={(e) => { e.preventDefault(); void ask(q) }}>
          <input value={q} onChange={(e) => setQ(e.target.value)} disabled={stopped} placeholder={stopped ? (ar ? 'المساعد متوقف' : 'Assistant stopped') : (ar ? 'اكتب سؤالك…' : 'Ask a question…')} aria-label="Question" />
          <button className="btn primary" disabled={stopped || thinking || !q.trim()} aria-label="Send"><Send size={16} /></button>
        </form>
      </section>
      <aside className="card governance">
        <h3><ShieldCheck size={16} /> {ar ? 'حوكمة المساعد' : 'Assistant governance'}</h3>
        <dl className="kv">
          <dt>{ar ? 'المالك' : 'Owner'}</dt><dd>{ar ? 'إدارة القنوات الرقمية' : 'Digital channels team'}</dd>
          <dt>{ar ? 'النطاق' : 'Scope'}</dt><dd>{ar ? 'بيانات المتعامل نفسه فقط' : "The customer's own data only"}</dd>
          <dt>{ar ? 'الأدوات' : 'Tools'}</dt><dd className="mono" dir="ltr" style={{ fontSize: 10, whiteSpace: 'nowrap', textAlign: 'start' }}>/v1/me/violations<br />/v1/me/black-point-account<br />/v1/me/payables</dd>
          <dt>{ar ? 'تلقائي' : 'Autonomous'}</dt><dd>{ar ? 'القراءة والشرح فقط' : 'Read and explain only'}</dd>
          <dt>{ar ? 'بموافقة' : 'Needs approval'}</dt><dd>{ar ? 'أي دفع أو اعتراض: يقوم به المتعامل' : 'Any payment or objection: the customer does it'}</dd>
          <dt>{ar ? 'التدقيق' : 'Audit'}</dt><dd>{ar ? 'كل قراءة تُسجَّل بقناة ASSISTANT' : 'Every read is audited with channel ASSISTANT'}</dd>
          <dt>{ar ? 'الإيقاف' : 'Emergency stop'}</dt><dd>{stopped ? <span className="pill bad">{ar ? 'مفعّل' : 'ON'}</span> : <span className="pill ok">{ar ? 'جاهز' : 'ready'}</span>}</dd>
        </dl>
        <p className="muted" style={{ fontSize: 12, marginBottom: 0 }}>{ar ? 'معاينة قائمة على القواعد. في المرحلة 13 يحلّ وكيل الذكاء الاصطناعي محل الصياغة خلف الأدوات والصلاحيات نفسها.' : 'Rule-based preview. In Increment 13 the AI agent replaces the wording behind the same tools, permissions and stop switch.'}</p>
      </aside>

      {/* Assistant requirements progress — AI-003, AI-006, AI-033, AI-035, CC-073, SEC-033, CC-023, VF-050, FN-001, VF-071 */}
      <section className="card req-progress page-enter" style={{ gridColumn: '1 / -1', marginTop: 18 }}>
      <div className="req-progress-head">
        <ListChecks size={16} />
        <span>{ar ? 'متطلبات المساعد' : 'Assistant requirements'}</span>
        <span className="pill warn">{ar ? 'معاينة' : 'preview'}</span>
      </div>
      <div className="req-progress-items">
        {[
          { id: 'AI-003', en: 'AI agents run on authority infrastructure with same APIs/permissions', ar: 'وكلاء الذكاء الاصطناعي يعملون على بنية السلطة بنفس واجهات البرمجة/الصلاحيات' },
          { id: 'AI-006', en: 'Human in the loop for every high-impact action', ar: 'إنسان في الحلقة لكل إجراء عالي التأثير' },
          { id: 'AI-033', en: 'Behaviour monitoring of agents; per-agent emergency stop', ar: 'مراقبة سلوك الوكلاء؛ إيقاف طارئ لكل وكيل' },
          { id: 'AI-035', en: 'Adversarial testing (prompt injection, poisoning, evasion)', ar: 'اختبارات الخصومة (حقن أوامر، تسميم، تهرب)' },
          { id: 'CC-073', en: 'Reads customer preferences from notification-service', ar: 'يقرأ تفضيلات المتعامل من خدمة الإشعارات' },
          { id: 'SEC-033', en: 'Kill switch revokes all agent tokens and assignments', ar: 'مفتاح الإيقاف يلغي جميع رموز ومهام الوكيل' },
          { id: 'CC-023', en: 'Explains transaction status history', ar: 'يشرح سجل حالات المعاملات' },
          { id: 'VF-050', en: 'Explains objection process and pause effect', ar: 'يشرح عملية الاعتراض وتأثير التوقف' },
          { id: 'FN-001', en: 'Shows payables and receipt from payment-service', ar: 'يعرض المستحقات والإيصال من خدمة الدفع' },
          { id: 'VF-071', en: 'Explains instalment plan schedule and minimum', ar: 'يشرح جدول خطة التقسيط والحد الأدنى' },
        ].map((r) => (
          <div key={r.id} className="req-progress-row">
            <span className="req-progress-id mono" style={{ color: 'var(--uae-amber)' }}>{r.id}</span>
            <span className="req-progress-text" style={{ flex: 1 }}>{ar ? r.ar : r.en}</span>
            <span className="pill warn">{ar ? 'مخطط' : 'planned'}</span>
          </div>
        ))}
      </div>
    </section>
    </div>
  )
}
