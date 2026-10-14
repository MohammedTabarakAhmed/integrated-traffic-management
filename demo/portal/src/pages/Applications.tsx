import { useState } from 'react'
import { Ban, FileSearch, ListChecks } from 'lucide-react'
import { ApiError, call, when } from '../api'
import { Card, StatusPill } from '../components'
import { useI18n } from '../i18n'
import { nameOf } from '../names'
import { reasonText, RuleList } from '../rules'

// Applications of a traffic file (CC-021, CC-022, CC-025), as dev.checker (TRANSACTION_OFFICER): list them and cancel
// one that is still a Draft or Pending payment, with a reason. transaction-service refuses any other state
// (ILLEGAL_TRANSITION) and keeps the cancelled application and its history; nothing is deleted.

type Txn = { reference: string; serviceCode: string; status: string; channel: string; createdAt: string; region: string }
const CANCELLABLE = ['DRAFT', 'PENDING_PAYMENT']

export default function Applications({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [file, setFile] = useState('00000000018')
  const [items, setItems] = useState<Txn[] | null>(null)
  const [err, setErr] = useState('')
  const [cancelling, setCancelling] = useState<Txn | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)

  const find = async () => {
    setErr(''); setItems(null)
    try {
      const r = await call<{ items: Txn[] }>('checker', 'GET', `/v1/transactions?applicantFileNumber=${encodeURIComponent(file.trim())}&limit=50`, undefined, lang)
      setItems(r.items)
    } catch (e) { setErr((e as Error).message); setItems([]) }
  }
  const cancel = async () => {
    if (!cancelling) return
    setBusy(true)
    try {
      await call('checker', 'POST', `/v1/transactions/${cancelling.reference}/cancellations`, { reason: reason.trim() }, lang)
      notify(`${cancelling.reference}: ${ar ? 'أُلغيت' : 'cancelled'}`)
      setCancelling(null); setReason(''); void find()
    } catch (e) {
      notify(reasonText(e instanceof ApiError ? String(e.problem.code ?? '') : '', (e as Error).message, lang), true)
    } finally { setBusy(false) }
  }
  const reasonRules = [{ ok: reason.trim().length >= 3 && reason.length <= 500, en: 'A reason of 3 to 500 characters', ar: 'سبب من 3 إلى 500 حرف' }]

  return (
    <Card title={ar ? 'طلبات ملف مروري' : 'Applications of a traffic file'} icon={<ListChecks size={16} />} right={<span className="pill">CC-021 · CC-022</span>}>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 12 }}>
        <label className="field" style={{ margin: 0, flex: '1 1 220px' }}><span>{ar ? 'رقم الملف المروري' : 'Traffic file number'}</span>
          <input value={file} onChange={(e) => setFile(e.target.value.toUpperCase())} onKeyDown={(e) => e.key === 'Enter' && find()} dir="ltr" /></label>
        <button className="btn primary" onClick={find} disabled={!/^[A-Z0-9][A-Z0-9-]{0,19}$/.test(file.trim())}><FileSearch size={16} /> {ar ? 'بحث' : 'Find'}</button>
      </div>
      {err && <p role="alert" style={{ color: 'var(--red)', fontSize: 13 }}>{err}</p>}
      {items !== null && (items.length === 0 ? <div className="empty">{ar ? 'لا طلبات لهذا الملف' : 'No applications for this file'}</div> : (
        <div className="list">{items.map((x) => (
          <div className="row" key={x.reference}>
            <div className="grow">
              <div className="title mono">{x.reference}</div>
              <div className="meta"><span>{nameOf(x.serviceCode, lang)}</span><span>{x.channel}</span><span>{when(x.createdAt, lang)}</span></div>
            </div>
            <StatusPill s={x.status} />
            <button className="btn danger" disabled={!CANCELLABLE.includes(x.status)} title={CANCELLABLE.includes(x.status) ? '' : (ar ? 'المسودة أو بانتظار الدفع فقط' : 'Only Draft or Pending payment can be cancelled')}
              onClick={() => { setCancelling(x); setReason('') }}><Ban size={15} /> {ar ? 'إلغاء' : 'Cancel'}</button>
          </div>))}</div>
      ))}
      {cancelling && (
        <div className="banner" style={{ marginTop: 12, display: 'block' }}>
          <b>{ar ? 'إلغاء الطلب' : 'Cancel application'} <span className="mono">{cancelling.reference}</span></b>
          <label className="field" style={{ marginTop: 8 }}><span>{ar ? 'السبب (يُحفظ في السجل)' : 'Reason (kept in the record)'}</span>
            <textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} /></label>
          <RuleList rules={reasonRules} lang={lang} />
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <button className="btn danger" disabled={busy || !reasonRules[0].ok} onClick={cancel}><Ban size={15} /> {ar ? 'تأكيد الإلغاء' : 'Confirm cancellation'}</button>
            <button className="btn" onClick={() => setCancelling(null)}>{ar ? 'تراجع' : 'Keep it'}</button>
          </div>
        </div>
      )}
      <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{ar
        ? 'الإلغاء لا يحذف شيئًا: تبقى المعاملة وسجل حالتها والسبب، ويُسجَّل في التدقيق.'
        : 'Cancelling deletes nothing: the application, its status history and the reason are kept and audited.'}</p>
    </Card>
  )
}
