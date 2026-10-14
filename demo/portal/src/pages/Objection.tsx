import { useEffect, useState } from 'react'
import { CheckCircle2, Gavel, Paperclip, X } from 'lucide-react'
import { ApiError, call, when } from '../api'
import { load } from '../auth'
import { useI18n } from '../i18n'
import { fileRules, reasonText, RuleList } from '../rules'
import { sendContent } from './Documents'

const reasons: [string, string, string][] = [
  ['NOT_THE_DRIVER', 'I was not driving', 'لم أكن أقود المركبة'],
  ['WRONG_PLATE', 'Wrong plate read', 'قراءة خاطئة للوحة'],
  ['SIGN_NOT_VISIBLE', 'Sign or signal not visible', 'الإشارة غير واضحة'],
  ['EMERGENCY', 'Medical or other emergency', 'حالة طارئة'],
]

type DocType = { code: string; mediaTypes: string[]; maxBytes: number }
type Filed = { id: string; violationNumber: string; status: string; submittedAt: string; reasonCode: string; evidenceIds: string[] }
const EVIDENCE = 'SUPPORTING_DOCUMENT'

// Online objection (VF-050): the fine pauses while the objection waits (VF-052). Evidence is optional: the file is
// declared and uploaded to document-service (scanned like any upload, CC-080), and its id goes with the objection.
export default function Objection({ number, onClose, onDone }: { number: string; onClose: () => void; onDone: (msg: string) => void }) {
  const { t, lang } = useI18n()
  const ar = lang === 'ar'
  const [reason, setReason] = useState(reasons[0][0])
  const [text, setText] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [type, setType] = useState<DocType | null>(null)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState<'' | 'upload' | 'send'>('')
  const [filed, setFiled] = useState<Filed | null>(null)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    addEventListener('keydown', onKey)
    return () => removeEventListener('keydown', onKey)
  }, [onClose])
  useEffect(() => {
    call<{ items: DocType[] }>('customer', 'GET', '/v1/document-types', undefined, lang)
      .then((r) => setType(r.items.find((x) => x.code === EVIDENCE) ?? null)).catch(() => setType(null))
  }, [lang])

  const rules = file && type ? fileRules(file, type.mediaTypes, type.maxBytes) : []
  const fileOk = !file || (!!type && rules.every((r) => r.ok))

  const submit = async () => {
    setErr('')
    try {
      const evidenceIds: string[] = []
      if (file && type) {
        setBusy('upload')
        const me = load('customer')?.claims.sub
        const d = await call<{ id: string }>('customer', 'POST', '/v1/documents',
          { type: EVIDENCE, ownerType: 'CUSTOMER', ownerId: me, title: `Objection evidence ${number}` }, lang)
        const up = await sendContent(d.id, file)
        if (up.status !== 'AVAILABLE') throw new Error(ar ? `لم يُقبل الملف: ${up.status}` : `The file was not accepted: ${up.status}`)
        evidenceIds.push(d.id)
      }
      setBusy('send')
      const o = await call<Filed>('customer', 'POST', `/v1/me/violations/${number}/objections`,
        { reasonCode: reason, explanation: text.trim(), evidenceIds }, lang)
      setFiled(o)
    } catch (e) {
      const code = e instanceof ApiError ? String(e.problem.code ?? '') : ''
      setErr(reasonText(code, (e as Error).message, lang))
    } finally { setBusy('') }
  }

  const close = () => (filed ? onDone(`${t.objectionSent} · ${filed.status}`) : onClose())
  return (
    <div className="modal-back" role="dialog" aria-modal="true" aria-labelledby="obj-title" onClick={close}>
      <div className="card modal" onClick={(e) => e.stopPropagation()}>
        <button className="btn ghost close" onClick={close} aria-label="Close"><X size={18} /></button>
        <div className="ico cyan" style={{ marginBottom: 14 }}><Gavel size={18} /></div>
        <h2 id="obj-title" style={{ margin: '0 0 4px' }}>{t.objectTitle}</h2>
        <p className="muted mono" style={{ marginTop: 0 }}>{number} <span className="pill">VF-050</span></p>
        {filed ? (
          <div className="result ok" role="status">
            <div className="big"><CheckCircle2 color="var(--green)" /> {t.objectionSent}</div>
            <dl className="kv">
              <dt>{ar ? 'الحالة' : 'Status'}</dt><dd><span className="pill info">{filed.status}</span></dd>
              <dt>{ar ? 'المرجع' : 'Reference'}</dt><dd className="mono" style={{ fontSize: 12 }}>{filed.id}</dd>
              <dt>{ar ? 'السبب' : 'Reason'}</dt><dd>{reasons.find((r) => r[0] === filed.reasonCode)?.[ar ? 2 : 1] ?? filed.reasonCode}</dd>
              <dt>{ar ? 'المرفقات' : 'Evidence'}</dt><dd>{filed.evidenceIds.length ? `${filed.evidenceIds.length} ${ar ? 'ملف (تم فحصه)' : 'file (scanned)'}` : (ar ? 'لا شيء' : 'None')}</dd>
              <dt>{ar ? 'أُرسل' : 'Sent'}</dt><dd>{when(filed.submittedAt, lang)}</dd>
            </dl>
            <p className="muted" style={{ fontSize: 13, margin: '6px 0 0' }}>{ar ? 'الغرامة متوقفة حتى يبت المشرف في الاعتراض (المكتب الخلفي).' : 'The fine is paused until a supervisor decides (Back office).'}</p>
            <button className="btn primary" style={{ justifySelf: 'start', marginTop: 6 }} onClick={close}>{ar ? 'تم' : 'Done'}</button>
          </div>
        ) : (
          <>
            <label className="field"><span>{t.objectReason}</span>
              <select value={reason} onChange={(e) => setReason(e.target.value)}>
                {reasons.map(([c, en, a]) => <option key={c} value={c}>{ar ? a : en}</option>)}
              </select>
            </label>
            <label className="field"><span>{t.objectExplain}</span>
              <textarea rows={4} value={text} onChange={(e) => setText(e.target.value)} maxLength={2000} />
            </label>
            {type && (
              <label className="field"><span><Paperclip size={13} /> {ar ? 'دليل (اختياري)' : 'Evidence (optional)'}</span>
                <input type="file" accept={type.mediaTypes.join(',')} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
              </label>
            )}
            {file && <RuleList rules={rules} lang={lang} />}
            <p className="muted" style={{ fontSize: 13 }}>{t.objectNote}</p>
            {err && <p role="alert" style={{ color: 'var(--red)' }}>{err}</p>}
            <button className="btn primary lg" style={{ width: '100%', justifyContent: 'center' }} disabled={busy !== '' || !text.trim() || !fileOk} onClick={submit}>
              {busy === 'upload' ? (ar ? 'جارٍ رفع الدليل وفحصه…' : 'Uploading and scanning the evidence…') : busy === 'send' ? (ar ? 'جارٍ الإرسال…' : 'Sending…') : t.objectSubmit}
            </button>
          </>
        )}
      </div>
    </div>
  )
}
