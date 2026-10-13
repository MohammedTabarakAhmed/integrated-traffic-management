import { useCallback, useEffect, useState } from 'react'
import { AlertOctagon, Bell, Camera, CarFront, CreditCard, FileText, Inbox, MapPin, ReceiptText, Wallet } from 'lucide-react'
import { call, money, when } from '../api'
import { load } from '../auth'
import Objection from './Objection'
import { Card, Drawer, Gauge, Kpi, Skeleton, StatusPill, Timeline } from '../components'
import { useI18n } from '../i18n'
import { nameOf } from '../names'

type Violation = {
  number: string; catalogueCode: string; occurredAt: string; region: string; location: string; plate?: string | null
  fineMinor: number; blackPoints: number; status: string
}
type Account = {
  fileNumber: string; activePoints: number; threshold: number
  activeSuspension?: { id: string; from: string; until: string; ordinal: number } | null
  suspensions: { id: string; pointsAtOrder: number }[]
}
type Txn = { reference: string; serviceCode: string; module: string; status: string; createdAt: string; channel: string }
type History = { sequence: number; previousStatus?: string | null; status: string; reason: string; note?: string | null; actor: { username?: string | null }; channel: string; changedAt: string }
type Message = { id: string; subject?: string | null; body: string; createdAt: string; readAt?: string | null; category: string }
type Payable = { payable: { id: string; itemType: string; itemRef: string; total: number; currency: string; status: string }; paused: boolean }
type FileT = {
  fileNumber: string; status: string; regionOpened: string; category: string; type: string
  identity?: { name?: Record<string, string> } | null
  profile?: { mobile?: string | null; email?: string | null } | null
}

export type Data = { file: FileT; violations: Violation[]; account: Account; txns: Txn[]; inbox: Message[]; payables: Payable[] }

export async function loadMine(lang: string): Promise<Data> {
  const get = <T,>(p: string) => call<T>('customer', 'GET', p, undefined, lang)
  const [file, v, account, txns, inbox, payables] = await Promise.all([
    get<FileT>('/v1/me/traffic-file'),
    get<{ items: Violation[] }>('/v1/me/violations'),
    get<Account>('/v1/me/black-point-account'),
    get<{ items: Txn[] }>('/v1/me/transactions'),
    get<{ items: Message[] }>('/v1/me/inbox').catch(() => ({ items: [] as Message[] })),
    get<{ items: Payable[] }>('/v1/me/payables').catch(() => ({ items: [] as Payable[] })),
  ])
  return { file, violations: v.items, account, txns: txns.items, inbox: inbox.items, payables: payables.items }
}


export default function Dashboard({ onError, notify }: { onError: (e: unknown) => void; notify: (text: string) => void }) {
  const { t, lang } = useI18n()
  const [d, setD] = useState<Data | null>(null)
  const [paying, setPaying] = useState(false)
  const [objecting, setObjecting] = useState<string | null>(null)
  const [txn, setTxn] = useState<{ x: Txn; history: History[] | null } | null>(null)
  const [msg, setMsg] = useState<Message | null>(null)

  // Transaction detail: its status history, oldest first (CC-023).
  const openTxn = (x: Txn) => {
    setTxn({ x, history: null })
    call<{ items: History[] }>('customer', 'GET', `/v1/me/transactions/${x.reference}/status-history`, undefined, lang)
      .then((h) => setTxn({ x, history: h.items })).catch(onError)
  }
  // Opening a message marks it read (the unread count drops).
  const openMsg = (m: Message) => {
    setMsg(m)
    if (!m.readAt) call('customer', 'POST', `/v1/me/inbox/${m.id}/reads`, undefined, lang).then(() => refresh()).catch(() => {})
  }

  const refresh = useCallback(() => {
    loadMine(lang).then(setD).catch(onError)
  }, [lang, onError])
  useEffect(() => {
    refresh()
    const id = setInterval(refresh, 5000) // live: the control room changes points while we watch
    return () => clearInterval(id)
  }, [refresh])

  if (!d) {
    return (
      <div className="grid g-4">
        {[0, 1, 2, 3].map((i) => <section key={i} className="card"><Skeleton /></section>)}
      </div>
    )
  }

  const open = d.violations.filter((v) => v.status === 'OPEN')
  const due = d.payables.filter((p) => p.payable.status === 'OPEN' && !p.paused)
  const paused = new Set(d.payables.filter((p) => p.paused).map((p) => p.payable.itemRef))
  const dueTotal = due.reduce((s, p) => s + p.payable.total, 0)
  const currency = due[0]?.payable.currency ?? 'AED'
  const name = d.file.identity?.name?.[lang === 'ar' ? 'nl' : 'en'] ?? d.file.identity?.name?.en
  const susp = d.account.activeSuspension
  const holder = name ?? `ID •••• ${String(load('customer')?.claims.national_id ?? '').slice(-4)}`

  // Pay all: one basket of the open items, then the gateway's hosted checkout (FN-001, CC-047).
  const payAll = async () => {
    setPaying(true)
    try {
      const basket = await call<{ id: string }>('customer', 'POST', '/v1/me/baskets', { payableIds: due.map((p) => p.payable.id) }, lang)
      const started = await call<{ redirectUrl: string | null }>('customer', 'POST', `/v1/baskets/${basket.id}/payments`,
        { method: 'GATEWAY_CARD', returnUrl: `${location.origin}/#/receipt` }, lang)
      if (started.redirectUrl) location.assign(started.redirectUrl)
    } catch (e) {
      onError(e)
      setPaying(false)
    }
  }

  return (
    <>
      {objecting && (
        <Objection number={objecting} onClose={() => setObjecting(null)}
          onDone={(msg) => { setObjecting(null); notify(msg); refresh() }} />
      )}
      {susp && (
        <div className="banner" role="alert">
          <AlertOctagon color="var(--red)" />
          <div>
            <b>{t.suspended}</b> <span className="muted">{t.until} {new Date(susp.until).toLocaleDateString(lang === 'ar' ? 'ar-AE' : 'en-GB')}</span>
          </div>
        </div>
      )}
      <div className="grid g-4" style={{ marginBottom: 18 }}>
        <Kpi icon={<FileText size={18} />} value={<span className="mono">{d.file.fileNumber}</span>} label={`${t.file} · ${holder}`} />
        <Kpi tone="amber" icon={<Camera size={18} />} value={open.length} label={t.openFines} />
        <section className="card kpi violet">
          <div style={{ display: 'flex', alignItems: 'flex-start' }}>
            <div className="icon"><Wallet size={18} /></div>
            <button className="btn primary" style={{ marginInlineStart: 'auto', padding: '8px 12px' }} onClick={payAll} disabled={paying || due.length === 0}>
              <CreditCard size={16} /> {paying ? t.paying : due.length ? t.payAll : t.nothingDue}
            </button>
          </div>
          <div className="v">{money(dueTotal, currency, lang)}</div>
          <div className="l">{t.totalDue}</div>
        </section>
        <Kpi tone="green" icon={<ReceiptText size={18} />} value={d.txns.length} label={t.transactions} />
      </div>
      <div className="grid g-3">
        <Card title={t.points} icon={<CarFront size={16} />} right={<span className="pill info"><span className="dot live" /> live</span>}>
          <Gauge value={susp ? d.account.suspensions.find((x) => x.id === susp.id)?.pointsAtOrder ?? d.account.activePoints : d.account.activePoints}
            max={d.account.threshold} label={`${t.pointsOf} ${d.account.threshold}`} />
          <p className="muted" style={{ textAlign: 'center', fontSize: 13, margin: '6px 0 0' }}>{t.pointsHint}</p>
        </Card>
        <Card className="span-2" title={t.violations} icon={<Camera size={16} />} right={<span className="pill">{d.violations.length}</span>}>
          {d.violations.length === 0 ? <div className="empty">{t.noViolations}</div> : (
            <div className="list">
              {d.violations.slice(0, 6).map((v) => (
                <div className="row" key={v.number}>
                  <div className={`ico ${v.status === 'PAID' ? 'green' : 'red'}`}><Camera size={17} /></div>
                  <div className="grow">
                    <div className="title">{nameOf(v.catalogueCode, lang)}</div>
                    <div className="meta">
                      <span className="mono">{v.number}</span>
                      <span><MapPin size={12} /> {v.location}</span>
                      <span>{when(v.occurredAt, lang)}</span>
                      {v.blackPoints > 0 && <span style={{ color: 'var(--amber)' }}>+{v.blackPoints} pts</span>}
                    </div>
                  </div>
                  <div className="amount">{money(v.fineMinor, currency, lang)}</div>
                  {paused.has(v.number) ? <span className="pill info">{t.underObjection}</span> : <StatusPill s={v.status} />}
                  {v.status === 'OPEN' && !paused.has(v.number) && (
                    <button className="btn ghost" style={{ padding: '6px 10px' }} onClick={() => setObjecting(v.number)}>{t.object}</button>
                  )}
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card className="span-2" title={t.transactions} icon={<ReceiptText size={16} />}>
          {d.txns.length === 0 ? <div className="empty">{t.noTransactions}</div> : (
            <div className="list">
              {d.txns.slice(0, 5).map((x) => (
                <div className="row clickable" key={x.reference} onClick={() => openTxn(x)} role="button" tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && openTxn(x)}>
                  <div className="ico cyan"><ReceiptText size={17} /></div>
                  <div className="grow">
                    <div className="title">{nameOf(x.serviceCode, lang)}</div>
                    <div className="meta"><span className="mono">{x.reference}</span><span>{nameOf(x.module, lang)}</span><span>{when(x.createdAt, lang)}</span></div>
                  </div>
                  <StatusPill s={x.status} />
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card title={t.inbox} icon={<Inbox size={16} />} right={<span className="pill">{d.inbox.filter((m) => !m.readAt).length}</span>}>
          {d.inbox.length === 0 ? <div className="empty">{t.noMessages}</div> : (
            <div className="list">
              {d.inbox.slice(0, 6).map((m) => (
                <div className="row clickable" key={m.id} onClick={() => openMsg(m)} role="button" tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && openMsg(m)}>
                  <div className="ico"><Bell size={16} /></div>
                  <div className="grow">
                    <div className="title">{m.subject ?? m.category}</div>
                    <div className="meta clamp2" style={{ display: '-webkit-box' }}>{m.body}</div>
                  </div>
                  {!m.readAt && <span className="unread-dot" aria-label="unread" />}
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
      <Drawer open={!!txn} onClose={() => setTxn(null)} title={txn ? nameOf(txn.x.serviceCode, lang) : ''}>
        {txn && (
          <>
            <dl className="kv" style={{ marginTop: 0 }}>
              <dt>{t.reference}</dt><dd className="mono">{txn.x.reference}</dd>
              <dt>{t.statusLabel}</dt><dd><StatusPill s={txn.x.status} /></dd>
              <dt>{t.channel}</dt><dd>{txn.x.channel}</dd>
              <dt>{t.opened}</dt><dd>{when(txn.x.createdAt, lang)}</dd>
            </dl>
            <h3 className="drawer-sub">{t.history}</h3>
            {txn.history === null ? <Skeleton /> : (
              <Timeline items={txn.history.map((h) => ({
                title: <>{h.previousStatus ? `${t.status[h.previousStatus] ?? h.previousStatus} → ` : ''}{t.status[h.status] ?? h.status}</>,
                meta: <><span>{h.reason}</span><span>{h.actor?.username ?? '—'}</span><span>{h.channel}</span><span>{when(h.changedAt, lang)}</span></>,
                tone: ['APPROVED', 'COMPLETED', 'PAID', 'FULFILLED'].includes(h.status) ? 'ok' : ['REJECTED', 'CANCELLED'].includes(h.status) ? 'bad' : 'info',
              }))} />
            )}
          </>
        )}
      </Drawer>
      <Drawer open={!!msg} onClose={() => setMsg(null)} title={msg ? (msg.subject ?? msg.category) : ''}>
        {msg && (
          <>
            <div className="meta muted" style={{ display: 'flex', gap: 10, marginBottom: 12, fontSize: 13 }}><span>{when(msg.createdAt, lang)}</span><span className="pill">{msg.category}</span></div>
            <div className="msg-body">{msg.body}</div>
          </>
        )}
      </Drawer>
    </>
  )
}
