import { useCallback, useEffect, useMemo, useState } from 'react'
import { CalendarCheck, CalendarDays, CalendarX, Clock, Layers, RefreshCw } from 'lucide-react'
import { ApiError, call, money, when } from '../api'
import { load } from '../auth'
import { Card, Skeleton, StatusPill } from '../components'
import { useI18n } from '../i18n'
import { nameOf } from '../names'

// My services (demo D4.2, D4.3): pay open fines in monthly instalments (VF-071) and book a service-centre appointment
// (CC-090..CC-092), both over the customer's own APIs.

type V = { number: string; catalogueCode: string; fineMinor: number; occurredAt: string; location: string }
type Plan = { number: string; totalMinor: number; currency: string; months: number; status: string; createdAt: string; violationNumbers: string[]
  instalments: { seq: number; dueDate: string; amountMinor: number; paid: boolean }[] }
type Slot = { resourceCode: string; start: string; end: string; free: number }
type Booking = { id: string; serviceCode: string; centreCode: string; start: string; end: string; status: string; changes: number }

// violation-service's instalment terms (itms.violations.instalments, VF-071; BRD defaults, Q-083 interim). No API
// exposes them yet, so the page mirrors the defaults to guide the citizen; the service still checks every request.
const TERMS = { minMonths: 3, maxMonths: 12, minimumTotalMinor: 500000 }

// The service's split (InstalmentPlan.schedule): equal parts, the remainder on the last one, the first due one month
// after today (Dubai), a month-end date clamped like java.time plusMonths.
function previewSchedule(total: number, months: number) {
  const today = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Dubai' }))
  const base = Math.floor(total / months)
  return Array.from({ length: months }, (_, k) => {
    const i = k + 1
    const y = today.getFullYear(), m = today.getMonth() + i
    const last = new Date(y, m + 1, 0).getDate()
    const due = new Date(y, m, Math.min(today.getDate(), last))
    const iso = `${due.getFullYear()}-${String(due.getMonth() + 1).padStart(2, '0')}-${String(due.getDate()).padStart(2, '0')}`
    return { seq: i, dueDate: iso, amountMinor: i === months ? total - base * (months - 1) : base }
  })
}

const CENTRE = 'DEMO_DXB_HQ'
const SERVICE = 'DEMO_EYE_TEST'
const utc = (iso: string) => new Date(iso).toISOString().replace(/\.\d+Z$/, 'Z')
const day = (d: Date) => d.toISOString().slice(0, 10)
const time = (iso: string, lang: string) => new Intl.DateTimeFormat(lang === 'ar' ? 'ar-AE' : 'en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Dubai' }).format(new Date(iso))

function Instalments({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [open, setOpen] = useState<V[] | null>(null)
  const [plans, setPlans] = useState<Plan[]>([])
  const [paused, setPaused] = useState<Set<string>>(new Set())
  const [pick, setPick] = useState<Set<string>>(new Set())
  const [months, setMonths] = useState(6)
  const [busy, setBusy] = useState(false)
  const [created, setCreated] = useState<string | null>(null)

  const reload = useCallback(() => {
    call<{ items: V[] }>('customer', 'GET', '/v1/me/violations?status=OPEN', undefined, lang).then((r) => setOpen(r.items)).catch(() => setOpen([]))
    call<{ items: Plan[] }>('customer', 'GET', '/v1/me/instalment-plans', undefined, lang).then((r) => setPlans(r.items)).catch(() => {})
    call<{ items: { payable: { itemRef: string }; paused: boolean }[] }>('customer', 'GET', '/v1/me/payables', undefined, lang)
      .then((r) => setPaused(new Set(r.items.filter((x) => x.paused).map((x) => x.payable.itemRef)))).catch(() => {})
  }, [lang])
  useEffect(reload, [reload])

  // Fines already in an active plan are paid through it, and fines under objection are paused (VF-052).
  const planned = new Set(plans.filter((p) => p.status !== 'COMPLETED').flatMap((p) => p.violationNumbers))
  const eligible = open === null ? null : open.filter((v) => !planned.has(v.number) && !paused.has(v.number))
  const total = (eligible ?? []).filter((v) => pick.has(v.number)).reduce((s, v) => s + v.fineMinor, 0)
  const create = async () => {
    setBusy(true)
    try {
      const p = await call<Plan>('customer', 'POST', '/v1/me/instalment-plans', { violationNumbers: [...pick], months }, lang)
      notify(`${ar ? 'تم إنشاء خطة التقسيط' : 'Instalment plan created'} ${p.number}`)
      setCreated(p.number); setPick(new Set()); reload()
    } catch (e) {
      notify(e instanceof ApiError ? e.message : String(e), true)
    } finally { setBusy(false) }
  }

  return (
    <Card title={ar ? 'الدفع بالتقسيط' : 'Pay fines in instalments'} icon={<Layers size={16} />} right={<span className="pill">VF-071</span>}>
      <p className="muted" style={{ marginTop: 0, fontSize: 13.5 }}>{ar
        ? `اختر المخالفات المستحقة ومدة الخطة: من ${TERMS.minMonths} إلى ${TERMS.maxMonths} شهرًا، ومجموع لا يقل عن ${money(TERMS.minimumTotalMinor, 'AED', lang)} (الإعدادات الحالية للنظام).`
        : `Choose open fines and a duration: ${TERMS.minMonths} to ${TERMS.maxMonths} months, for a total of at least ${money(TERMS.minimumTotalMinor, 'AED', lang)} (the system's current settings).`}
        {' '}<span className="pill">VF-073</span></p>
      {eligible === null ? <Skeleton /> : eligible.length === 0 ? <div className="empty">{ar ? 'لا مخالفات مستحقة' : 'No open fines'}</div> : (
        <div className="list">
          {eligible.map((v) => (
            <label key={v.number} className="row clickable" style={{ cursor: 'pointer' }}>
              <input type="checkbox" checked={pick.has(v.number)} onChange={() => setPick((s) => { const n = new Set(s); if (n.has(v.number)) n.delete(v.number); else n.add(v.number); return n })} />
              <div className="grow"><div className="title">{nameOf(v.catalogueCode, lang)}</div><div className="meta"><span className="mono">{v.number}</span><span>{v.location}</span></div></div>
              <b>{money(v.fineMinor, 'AED', lang)}</b>
            </label>
          ))}
        </div>
      )}
      <div className="plan-box">
        <label className="field" style={{ margin: 0, flex: 1 }}><span>{ar ? 'عدد الأشهر' : 'Months'}: <b>{months}</b></span>
          <input type="range" min={TERMS.minMonths} max={TERMS.maxMonths} value={months} onChange={(e) => setMonths(Number(e.target.value))} /></label>
        <div className="plan-sum"><small>{ar ? 'القسط الشهري' : 'Monthly'}</small><b className="grad-text">{money(months ? Math.ceil(total / months) : 0, 'AED', lang)}</b>
          <small>{ar ? 'المجموع' : 'Total'} {money(total, 'AED', lang)}</small></div>
        <button className="btn primary" disabled={busy || pick.size === 0} onClick={create}>{ar ? 'إنشاء الخطة' : 'Create plan'}</button>
      </div>
      {pick.size > 0 && total < TERMS.minimumTotalMinor && (
        <p className="hint-warn">{ar
          ? `المجموع المختار ${money(total, 'AED', lang)} أقل من الحد الأدنى ${money(TERMS.minimumTotalMinor, 'AED', lang)}؛ سترفض الخدمة الخطة.`
          : `The chosen total ${money(total, 'AED', lang)} is below the minimum ${money(TERMS.minimumTotalMinor, 'AED', lang)}; the service will refuse the plan.`}</p>)}
      {pick.size > 0 && total >= months && (
        <>
          <h3 className="drawer-sub">{ar ? 'معاينة الجدول' : 'Schedule preview'} <span className="muted" style={{ fontWeight: 400, fontSize: 12 }}>
            {ar ? '· الجدول النهائي يصدر من الخدمة عند الإنشاء' : '· the final schedule comes from the service on creation'}</span></h3>
          <div className="schedule">{previewSchedule(total, months).map((i) => (
            <div key={i.seq} className="inst preview"><small>#{i.seq}</small><b>{money(i.amountMinor, 'AED', lang)}</b><small>{i.dueDate}</small></div>
          ))}</div>
        </>)}
      {plans.length > 0 && <h3 className="drawer-sub">{ar ? 'خططي' : 'My plans'}</h3>}
      {plans.map((p) => (
        <div key={p.number} className={`plan ${created === p.number ? 'fresh' : ''}`}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}><b className="mono">{p.number}</b><StatusPill s={p.status} />
            <span className="muted" style={{ marginInlineStart: 'auto' }}>{money(p.totalMinor, p.currency, lang)} · {p.months} × </span></div>
          <div className="schedule">{p.instalments.map((i) => (
            <div key={i.seq} className={`inst ${i.paid ? 'paid' : ''}`}><small>#{i.seq}</small><b>{money(i.amountMinor, p.currency, lang)}</b><small>{i.dueDate}</small></div>
          ))}</div>
        </div>
      ))}
    </Card>
  )
}

function Appointments({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const me = load('customer')?.claims.sub ?? ''
  const days = useMemo(() => Array.from({ length: 7 }, (_, i) => new Date(Date.now() + (i + 1) * 86_400_000)), [])
  const [date, setDate] = useState(day(days[0]))
  const [slots, setSlots] = useState<Slot[] | null>(null)
  const [bookings, setBookings] = useState<Booking[]>([])
  const [moving, setMoving] = useState<Booking | null>(null)
  const [busy, setBusy] = useState(false)

  const reload = useCallback(() => {
    setSlots(null)
    call<{ items: Slot[] }>('customer', 'GET', `/v1/slots?centreCode=${CENTRE}&serviceCode=${SERVICE}&from=${date}&to=${date}`, undefined, lang)
      .then((r) => setSlots(r.items)).catch(() => setSlots([]))
    call<{ items: Booking[] }>('customer', 'GET', `/v1/bookings?customerId=${encodeURIComponent(me)}&limit=20`, undefined, lang)
      .then((r) => setBookings(r.items.sort((a, b) => b.start.localeCompare(a.start)))).catch(() => {})
  }, [date, lang, me])
  useEffect(reload, [reload])

  const choose = async (s: Slot) => {
    setBusy(true)
    try {
      if (moving) {
        await call('customer', 'POST', `/v1/bookings/${moving.id}/changes`, { start: utc(s.start) }, lang)
        notify(ar ? 'تم تغيير الموعد' : 'Appointment moved'); setMoving(null)
      } else {
        await call('customer', 'POST', '/v1/bookings', { serviceCode: SERVICE, centreCode: CENTRE, start: utc(s.start), customerId: me }, lang)
        notify(ar ? 'تم حجز الموعد' : 'Appointment booked')
      }
      reload()
    } catch (e) { notify(e instanceof ApiError ? e.message : String(e), true) } finally { setBusy(false) }
  }
  const cancel = async (b: Booking) => {
    try { await call('customer', 'POST', `/v1/bookings/${b.id}/cancellations`, { reason: 'customer cancelled online' }, lang); notify(ar ? 'تم إلغاء الموعد' : 'Appointment cancelled'); reload() }
    catch (e) { notify(e instanceof ApiError ? e.message : String(e), true) }
  }

  return (
    <Card title={ar ? 'حجز موعد في مركز الخدمة' : 'Book a service-centre appointment'} icon={<CalendarDays size={16} />} right={<span className="pill">CC-090 · CC-091</span>}>
      <p className="muted" style={{ marginTop: 0, fontSize: 13.5 }}>{ar ? 'فحص النظر · مركز دبي الرئيسي. يمكن تغيير الموعد مرتين.' : 'Eye test · Dubai main centre. A booking can be changed twice.'}</p>
      {moving && <div className="banner info"><RefreshCw size={16} /><span>{ar ? 'اختر موعدًا جديدًا لـ' : 'Pick a new time for'} {when(moving.start, lang)}</span><button className="btn ghost" style={{ marginInlineStart: 'auto' }} onClick={() => setMoving(null)}>✕</button></div>}
      <div className="days">{days.map((d) => (
        <button key={day(d)} className={`day ${day(d) === date ? 'on' : ''}`} onClick={() => setDate(day(d))}>
          <small>{new Intl.DateTimeFormat(lang === 'ar' ? 'ar-AE' : 'en-GB', { weekday: 'short' }).format(d)}</small><b>{d.getDate()}</b>
        </button>))}</div>
      {slots === null ? <Skeleton /> : slots.length === 0 ? <div className="empty">{ar ? 'لا مواعيد متاحة' : 'No free slots'}</div> : (
        <div className="slots">{slots.slice(0, 24).map((s) => (
          <button key={s.start + s.resourceCode} className="slot" disabled={busy || s.free === 0} onClick={() => choose(s)}>
            <Clock size={13} /> {time(s.start, lang)}<small>{s.free} {ar ? 'متاح' : 'free'}</small>
          </button>))}</div>
      )}
      <h3 className="drawer-sub">{ar ? 'مواعيدي' : 'My appointments'}</h3>
      {bookings.length === 0 ? <div className="empty">{ar ? 'لا مواعيد' : 'No appointments yet'}</div> : (
        <div className="list">{bookings.slice(0, 5).map((b) => (
          <div className="row" key={b.id}>
            <div className={`ico ${b.status === 'BOOKED' ? 'cyan' : ''}`}>{b.status === 'BOOKED' ? <CalendarCheck size={16} /> : <CalendarX size={16} />}</div>
            <div className="grow"><div className="title">{when(b.start, lang)}</div><div className="meta"><span>{nameOf(b.centreCode, lang)}</span><span>{ar ? 'تغييرات' : 'changes'} {b.changes}/2</span></div></div>
            <StatusPill s={b.status} />
            {b.status === 'BOOKED' && <>
              <button className="btn ghost" onClick={() => setMoving(b)}>{ar ? 'تغيير' : 'Change'}</button>
              <button className="btn ghost" onClick={() => cancel(b)}>{ar ? 'إلغاء' : 'Cancel'}</button></>}
          </div>))}</div>
      )}
    </Card>
  )
}

export default function Services({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  return (
    <div className="grid g-2">
      <Instalments notify={notify} />
      <Appointments notify={notify} />
    </div>
  )
}
