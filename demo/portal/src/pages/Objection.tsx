import { useState } from 'react'
import { Gavel, X } from 'lucide-react'
import { call } from '../api'
import { useI18n } from '../i18n'

const reasons: [string, string, string][] = [
  ['NOT_THE_DRIVER', 'I was not driving', 'لم أكن أقود المركبة'],
  ['WRONG_PLATE', 'Wrong plate read', 'قراءة خاطئة للوحة'],
  ['SIGN_NOT_VISIBLE', 'Sign or signal not visible', 'الإشارة غير واضحة'],
  ['EMERGENCY', 'Medical or other emergency', 'حالة طارئة'],
]

// Online objection (VF-050): the fine pauses while the objection waits (VF-052).
export default function Objection({ number, onClose, onDone }: { number: string; onClose: () => void; onDone: (msg: string) => void }) {
  const { t, lang } = useI18n()
  const [reason, setReason] = useState(reasons[0][0])
  const [text, setText] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true); setErr('')
    try {
      const o = await call<{ id: string; status: string }>('customer', 'POST', `/v1/me/violations/${number}/objections`,
        { reasonCode: reason, explanation: text.trim() }, lang)
      onDone(`${t.objectionSent} · ${o.status}`)
    } catch (e) {
      setErr((e as Error).message)
      setBusy(false)
    }
  }

  return (
    <div className="modal-back" role="dialog" aria-modal="true" aria-labelledby="obj-title" onClick={onClose}>
      <div className="card modal" onClick={(e) => e.stopPropagation()}>
        <button className="btn ghost close" onClick={onClose} aria-label="Close"><X size={18} /></button>
        <div className="ico cyan" style={{ marginBottom: 14 }}><Gavel size={18} /></div>
        <h2 id="obj-title" style={{ margin: '0 0 4px' }}>{t.objectTitle}</h2>
        <p className="muted mono" style={{ marginTop: 0 }}>{number}</p>
        <label className="field"><span>{t.objectReason}</span>
          <select value={reason} onChange={(e) => setReason(e.target.value)}>
            {reasons.map(([c, en, ar]) => <option key={c} value={c}>{lang === 'ar' ? ar : en}</option>)}
          </select>
        </label>
        <label className="field"><span>{t.objectExplain}</span>
          <textarea rows={4} value={text} onChange={(e) => setText(e.target.value)} maxLength={2000} />
        </label>
        <p className="muted" style={{ fontSize: 13 }}>{t.objectNote}</p>
        {err && <p style={{ color: 'var(--red)' }}>{err}</p>}
        <button className="btn primary lg" style={{ width: '100%', justifyContent: 'center' }} disabled={busy || !text.trim()} onClick={submit}>
          {t.objectSubmit}
        </button>
      </div>
    </div>
  )
}
