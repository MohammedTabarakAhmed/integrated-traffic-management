import { useCallback, useEffect, useState } from 'react'
import { AlertOctagon, Bell, Camera, CarFront, FileText, Inbox, MapPin, ReceiptText, Wallet } from 'lucide-react'
import { call, money, when } from '../api'
import { Card, Gauge, Kpi, Skeleton, StatusPill } from '../components'
import { useI18n } from '../i18n'

type Violation = {
  number: string; catalogueCode: string; occurredAt: string; region: string; location: string; plate?: string | null
  fineMinor: number; blackPoints: number; status: string
}
type Account = {
  fileNumber: string; activePoints: number; threshold: number
  activeSuspension?: { from: string; until: string; ordinal: number } | null
}
type Txn = { reference: string; serviceCode: string; module: string; status: string; createdAt: string; channel: string }
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

const fineName = (code: string, lang: string) => {
  const names: Record<string, [string, string]> = {
    DEMO_SPEED: ['Speeding', 'تجاوز السرعة'],
    DEMO_PHONE: ['Phone while driving', 'استخدام الهاتف أثناء القيادة'],
    DEMO_PARK: ['Prohibited parking', 'وقوف في مكان غير مخصص'],
    DEMO_REDLIGHT: ['Red light', 'تجاوز الإشارة الحمراء'],
  }
  const n = names[code]
  return n ? n[lang === 'ar' ? 1 : 0] : code.replace(/_/g, ' ')
}

export default function Dashboard({ onError }: { onError: (e: unknown) => void }) {
  const { t, lang } = useI18n()
  const [d, setD] = useState<Data | null>(null)

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
  const dueTotal = due.reduce((s, p) => s + p.payable.total, 0)
  const currency = due[0]?.payable.currency ?? 'AED'
  const name = d.file.identity?.name?.[lang === 'ar' ? 'nl' : 'en'] ?? d.file.identity?.name?.en
  const susp = d.account.activeSuspension

  return (
    <>
      {susp && (
        <div className="banner" role="alert">
          <AlertOctagon color="var(--red)" />
          <div>
            <b>{t.suspended}</b> <span className="muted">{t.until} {new Date(susp.until).toLocaleDateString(lang === 'ar' ? 'ar-AE' : 'en-GB')}</span>
          </div>
        </div>
      )}
      <div className="grid g-4" style={{ marginBottom: 18 }}>
        <Kpi icon={<FileText size={18} />} value={<span className="mono">{d.file.fileNumber}</span>} label={`${t.file} · ${name ?? ''}`} />
        <Kpi tone="amber" icon={<Camera size={18} />} value={open.length} label={t.openFines} />
        <Kpi tone="violet" icon={<Wallet size={18} />} value={money(dueTotal, currency, lang)} label={t.totalDue} />
        <Kpi tone="green" icon={<ReceiptText size={18} />} value={d.txns.length} label={t.transactions} />
      </div>
      <div className="grid g-3">
        <Card title={t.points} icon={<CarFront size={16} />} right={<span className="pill info"><span className="dot live" /> live</span>}>
          <Gauge value={d.account.activePoints} max={d.account.threshold} label={`${t.pointsOf} ${d.account.threshold}`} />
          <p className="muted" style={{ textAlign: 'center', fontSize: 13, margin: '6px 0 0' }}>{t.pointsHint}</p>
        </Card>
        <Card className="span-2" title={t.violations} icon={<Camera size={16} />} right={<span className="pill">{d.violations.length}</span>}>
          {d.violations.length === 0 ? <div className="empty">{t.noViolations}</div> : (
            <div className="list">
              {d.violations.slice(0, 6).map((v) => (
                <div className="row" key={v.number}>
                  <div className={`ico ${v.status === 'PAID' ? 'green' : 'red'}`}><Camera size={17} /></div>
                  <div className="grow">
                    <div className="title">{fineName(v.catalogueCode, lang)}</div>
                    <div className="meta">
                      <span className="mono">{v.number}</span>
                      <span><MapPin size={12} /> {v.location}</span>
                      <span>{when(v.occurredAt, lang)}</span>
                      {v.blackPoints > 0 && <span style={{ color: 'var(--amber)' }}>+{v.blackPoints} pts</span>}
                    </div>
                  </div>
                  <div className="amount">{money(v.fineMinor, 'AED', lang)}</div>
                  <StatusPill s={v.status} />
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card className="span-2" title={t.transactions} icon={<ReceiptText size={16} />}>
          {d.txns.length === 0 ? <div className="empty">{t.noTransactions}</div> : (
            <div className="list">
              {d.txns.slice(0, 5).map((x) => (
                <div className="row" key={x.reference}>
                  <div className="ico cyan"><ReceiptText size={17} /></div>
                  <div className="grow">
                    <div className="title">{x.serviceCode.replace(/_/g, ' ')}</div>
                    <div className="meta"><span className="mono">{x.reference}</span><span>{x.module}</span><span>{when(x.createdAt, lang)}</span></div>
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
              {d.inbox.slice(0, 4).map((m) => (
                <div className="row" key={m.id}>
                  <div className="ico"><Bell size={16} /></div>
                  <div className="grow">
                    <div className="title">{m.subject ?? m.category}</div>
                    <div className="meta" style={{ display: 'block' }}>{m.body.slice(0, 90)}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </>
  )
}
