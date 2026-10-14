import { useCallback, useEffect, useState } from 'react'
import { Briefcase, CheckCircle2, Gavel, Hand, History, Lock, LockOpen, XCircle } from 'lucide-react'
import { ApiError, call, money, when } from '../api'
import { load, signInStaff } from '../auth'
import { Card, Drawer, Skeleton, StatusPill, tabFromLink, Timeline } from '../components'
import { useI18n } from '../i18n'
import { nameOf } from '../names'
import Applications from './Applications'
import NewFile from './NewFile'

// Back office (demo D4.4), as dev.checker: decide customer objections (VF-051, VF-052), work the case-officer queue
// (CC-060, CC-063) and lift a hold with a reason (CC-030). Every decision is audited by the services.

type Objection = { id: string; violationNumber: string; submittedBy: string; submittedAt: string; reasonCode: string; explanation: string; status: string }
type Item = { id: string; reference: string; serviceCode: string; stepCode: string; status: string; assignee?: string | null; createdAt: string; dueAt?: string | null; overdue?: boolean }
type ItemHistory = { action: string; stepCode: string; actorId: string; at: string; detail: Record<string, unknown> }
type Hold = { id: string; holdType: string; module: string; reason: string; status: string; startDate: string; endDate?: string | null; blockedServices: string[]; liftReason?: string | null }

const DEMO_FILE_ID = '784199012345671'
type Tab = 'objections' | 'queue' | 'holds' | 'newfile' | 'applications'

export default function BackOffice({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [ready, setReady] = useState(() => !!load('checker'))
  const [tab, setTab] = useState<Tab>(() => tabFromLink<Tab>(['objections', 'queue', 'holds', 'newfile', 'applications'], 'objections'))
  const [objections, setObjections] = useState<Objection[] | null>(null)
  const [items, setItems] = useState<Item[] | null>(null)
  const [holds, setHolds] = useState<Hold[] | null>(null)
  const [history, setHistory] = useState<{ item: Item; rows: ItemHistory[] | null } | null>(null)
  const [reduce, setReduce] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    const pw = import.meta.env.VITE_ITMS_CHECKER_PASSWORD as string | undefined
    if (!ready && pw) signInStaff('dev.checker', pw, 'checker').then(() => setReady(true)).catch(() => {})
  }, [ready])

  const c = <T,>(m: 'GET' | 'POST', p: string, b?: unknown) => call<T>('checker', m, p, b, lang)
  const fail = (e: unknown) => notify(e instanceof ApiError ? e.message : String(e), true)

  const reload = useCallback(async () => {
    c<{ items: Objection[] }>('GET', '/v1/objections/queue').then((r) => setObjections(r.items)).catch(() => setObjections([]))
    c<{ items: Item[] }>('GET', '/v1/work-items?list=TEAM&limit=50').then((r) => setItems(r.items)).catch(() => setItems([]))
    try {
      const f = await c<{ items: { fileNumber: string }[] }>('POST', '/v1/traffic-files/searches', { document: { type: 'NATIONAL_ID', nationalIdNo: DEMO_FILE_ID } })
      const acct = await c<{ suspensions: { holdId?: string | null }[] }>('GET', `/v1/black-point-accounts/${f.items[0].fileNumber}`)
      const ids = acct.suspensions.map((s) => s.holdId).filter((x): x is string => !!x)
      setHolds(await Promise.all(ids.map((id) => c<Hold>('GET', `/v1/holds/${id}`))))
    } catch { setHolds([]) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lang])
  useEffect(() => { if (ready) void reload() }, [ready, reload])

  const decide = async (o: Objection, decision: 'ACCEPT' | 'REDUCE' | 'REJECT') => {
    const body: Record<string, unknown> = { decision, reason: decision === 'ACCEPT' ? 'plate misread confirmed on the photo' : decision === 'REDUCE' ? 'first offence, fine reduced' : 'photo shows the vehicle clearly' }
    if (decision === 'REDUCE') body.reducedFineMinor = Math.round(Number(reduce[o.id] || '100') * 100)
    setBusy(o.id)
    try { await c('POST', `/v1/objections/${o.id}/decisions`, body); notify(`${o.violationNumber}: ${decision}`); void reload() } catch (e) { fail(e) } finally { setBusy(null) }
  }
  const claim = async (i: Item) => { setBusy(i.id); try { await c('POST', `/v1/work-items/${i.id}/claims`, {}); notify(`${i.reference}: ${ar ? 'تم الاستلام' : 'claimed'}`); void reload() } catch (e) { fail(e) } finally { setBusy(null) } }
  const close = async (i: Item, outcome: 'APPROVED' | 'REJECTED') => {
    setBusy(i.id)
    try { await c('POST', `/v1/work-items/${i.id}/decisions`, { outcome, reason: outcome === 'APPROVED' ? 'documents complete' : 'documents missing' }); notify(`${i.reference}: ${outcome}`); void reload() } catch (e) { fail(e) } finally { setBusy(null) }
  }
  const openHistory = (i: Item) => {
    setHistory({ item: i, rows: null })
    c<{ items: ItemHistory[] }>('GET', `/v1/work-items/${i.id}/history`).then((r) => setHistory({ item: i, rows: r.items })).catch(fail)
  }
  const lift = async (h: Hold) => {
    setBusy(h.id)
    try { await c('POST', `/v1/holds/${h.id}/lifts`, { reason: 'court order received; suspension served (demo)' }); notify(ar ? 'تم رفع الحجز' : 'Hold lifted'); void reload() } catch (e) { fail(e) } finally { setBusy(null) }
  }

  if (!ready) return <div className="card empty">{ar ? 'جارٍ دخول الموظف المدقق…' : 'Signing in the officer (dev.checker)…'}</div>

  const tabs: [Tab, string, number | undefined][] = [
    ['objections', ar ? 'الاعتراضات' : 'Objections', objections?.length],
    ['queue', ar ? 'قائمة العمل' : 'Work queue', items?.filter((i) => !['COMPLETED', 'CANCELLED'].includes(i.status)).length],
    ['holds', ar ? 'الحجوزات' : 'Holds', holds?.filter((h) => h.status === 'ACTIVE').length],
    ['newfile', ar ? 'ملف جديد' : 'New file', undefined],
    ['applications', ar ? 'الطلبات' : 'Applications', undefined],
  ]
  return (
    <>
      <div className="seg" role="tablist">
        {tabs.map(([k, label, n]) => (
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{label}{n !== undefined && <span className="pill">{n}</span>}</button>
        ))}
        <span className="pill ok" style={{ marginInlineStart: 'auto' }}><Hand size={12} /> dev.checker</span>
      </div>

      {tab === 'newfile' && <NewFile notify={notify} />}
      {tab === 'applications' && <Applications notify={notify} />}
      {tab === 'objections' && (
        <Card title={ar ? 'اعتراضات المتعاملين بانتظار القرار' : 'Customer objections awaiting a decision'} icon={<Gavel size={16} />} right={<span className="pill">VF-051 · VF-052</span>}>
          {objections === null ? <Skeleton /> : objections.length === 0 ? <div className="empty">{ar ? 'لا اعتراضات معلّقة. قدّم اعتراضًا من البوابة.' : 'No pending objections. Submit one from the citizen portal.'}</div> : (
            <div className="list">{objections.map((o) => (
              <div className="row" key={o.id} style={{ alignItems: 'flex-start' }}>
                <div className="ico cyan"><Gavel size={16} /></div>
                <div className="grow">
                  <div className="title mono">{o.violationNumber}</div>
                  <div className="meta"><span>{nameOf(o.reasonCode, lang)}</span><span>{when(o.submittedAt, lang)}</span></div>
                  <div className="quote">“{o.explanation}”</div>
                  <div className="actions">
                    <button className="btn" disabled={busy === o.id} onClick={() => decide(o, 'ACCEPT')}><CheckCircle2 size={15} /> {ar ? 'قبول (إلغاء الغرامة)' : 'Accept (cancel fine)'}</button>
                    <span className="reduce"><input inputMode="decimal" placeholder="100" value={reduce[o.id] ?? ''} onChange={(e) => setReduce((r) => ({ ...r, [o.id]: e.target.value }))} aria-label="Reduced fine" />
                      <button className="btn" disabled={busy === o.id} onClick={() => decide(o, 'REDUCE')}>{ar ? 'تخفيض' : 'Reduce to'} {money(Math.round(Number(reduce[o.id] || '100') * 100), 'AED', lang)}</button></span>
                    <button className="btn danger" disabled={busy === o.id} onClick={() => decide(o, 'REJECT')}><XCircle size={15} /> {ar ? 'رفض' : 'Reject'}</button>
                  </div>
                </div>
                <StatusPill s={o.status} />
              </div>))}</div>
          )}
        </Card>
      )}

      {tab === 'queue' && (
        <Card title={ar ? 'قائمة عمل موظف الحالات' : 'Case-officer work queue'} icon={<Briefcase size={16} />} right={<span className="pill">CC-060 · CC-063</span>}>
          {items === null ? <Skeleton /> : items.length === 0 ? <div className="empty">{ar ? 'القائمة فارغة' : 'The queue is empty'}</div> : (
            <div className="list">{items.map((i) => (
              <div className="row" key={i.id}>
                <div className={`ico ${i.overdue ? 'red' : 'cyan'}`}><Briefcase size={16} /></div>
                <div className="grow">
                  <div className="title mono">{i.reference}</div>
                  <div className="meta"><span>{nameOf(i.serviceCode, lang)}</span><span>{nameOf(i.stepCode, lang)}</span>{i.dueAt && <span>SLA {when(i.dueAt, lang)}</span>}<span>{i.assignee ?? (ar ? 'غير مسند' : 'unassigned')}</span></div>
                </div>
                <StatusPill s={i.status} />
                {i.status === 'OPEN' && !i.assignee && <button className="btn" disabled={busy === i.id} onClick={() => claim(i)}>{ar ? 'استلام' : 'Claim'}</button>}
                {i.assignee && !['COMPLETED', 'CANCELLED'].includes(i.status) && <>
                  <button className="btn" disabled={busy === i.id} onClick={() => close(i, 'APPROVED')}>{ar ? 'اعتماد' : 'Approve'}</button>
                  <button className="btn danger" disabled={busy === i.id} onClick={() => close(i, 'REJECTED')}>{ar ? 'رفض' : 'Reject'}</button></>}
                <button className="btn ghost" onClick={() => openHistory(i)} aria-label="History"><History size={15} /></button>
              </div>))}</div>
          )}
        </Card>
      )}

      {tab === 'holds' && (
        <Card title={ar ? 'الحجوزات على ملف المتعامل التجريبي' : 'Holds on the demo customer’s file'} icon={<Lock size={16} />} right={<span className="pill">CC-030</span>}>
          {holds === null ? <Skeleton /> : holds.length === 0 ? <div className="empty">{ar ? 'لا حجوزات. أوصل النقاط إلى 24 في غرفة التحكم.' : 'No holds. Take the points to 24 in the control room.'}</div> : (
            <div className="list">{holds.map((h) => (
              <div className="row" key={h.id}>
                <div className={`ico ${h.status === 'ACTIVE' ? 'red' : 'green'}`}>{h.status === 'ACTIVE' ? <Lock size={16} /> : <LockOpen size={16} />}</div>
                <div className="grow"><div className="title">{nameOf(h.holdType, lang)}</div>
                  <div className="meta"><span>{nameOf(h.module, lang)}</span><span>{h.startDate}{h.endDate ? ` → ${h.endDate}` : ''}</span><span>{h.liftReason ?? h.reason}</span></div></div>
                <StatusPill s={h.status} />
                {h.status === 'ACTIVE' && <button className="btn" disabled={busy === h.id} onClick={() => lift(h)}><LockOpen size={15} /> {ar ? 'رفع الحجز' : 'Lift hold'}</button>}
              </div>))}</div>
          )}
          <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{ar ? 'رفع الحجز يتطلب سببًا ويُسجَّل في سجل التدقيق؛ بعده يصبح تجديد الرخصة مسموحًا.' : 'Lifting needs a reason and is audited; afterwards the licence renewal check in the control room turns ALLOWED.'}</p>
        </Card>
      )}

      <Drawer open={!!history} onClose={() => setHistory(null)} title={history?.item.reference ?? ''}>
        {history && (history.rows === null ? <Skeleton /> : (
          <Timeline items={history.rows.map((h) => {
            const outcome = String(h.detail?.outcome ?? '')
            return {
              title: `${h.action} · ${h.stepCode}`,
              meta: <><span>{h.actorId.length > 24 ? `${h.actorId.slice(0, 8)}…` : h.actorId}</span>{outcome && <span>{outcome}</span>}{h.detail?.reason ? <span>{String(h.detail.reason)}</span> : null}<span>{when(h.at, lang)}</span></>,
              tone: outcome === 'APPROVED' ? 'ok' as const : outcome === 'REJECTED' ? 'bad' as const : 'info' as const,
            }
          })} />
        ))}
      </Drawer>
    </>
  )
}
