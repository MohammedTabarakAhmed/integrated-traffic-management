import { useCallback, useEffect, useMemo, useState } from 'react'
import { Download, FileCheck2, FileUp, ShieldCheck } from 'lucide-react'
import { ApiError, call, when } from '../api'
import { fresh, load } from '../auth'
import { Card, Skeleton, StatusPill } from '../components'
import { useI18n } from '../i18n'
import { allOk, digitsOf, emiratesIdRules, fileRules, licenceNumberRules, reasonText, RuleList, type Rule } from '../rules'

// My documents (CC-080, CC-081, CC-085): the citizen uploads their own Emirates ID copy, driving licence copy, photo or
// a supporting document. document-service owns the rules (file types and size per type), scans every upload for
// malware and keeps each document as a record; the citizen reaches only documents they own (owner = their login).

type DocType = { code: string; mediaTypes: string[]; maxBytes: number }
type Doc = { id: string; type: string; title?: string | null; status: string; mediaType?: string | null; size?: number | null; sha256?: string | null; createdAt: string }
type Kind = 'EID' | 'LICENCE' | 'PHOTO' | 'OTHER'

const KINDS: Record<Kind, { type: string; en: string; ar: string }> = {
  EID: { type: 'IDENTITY_DOCUMENT', en: 'Emirates ID (copy)', ar: 'الهوية الإماراتية (نسخة)' },
  LICENCE: { type: 'SUPPORTING_DOCUMENT', en: 'Driving licence (copy)', ar: 'رخصة القيادة (نسخة)' },
  PHOTO: { type: 'PERSONAL_PHOTO', en: 'Personal photo', ar: 'صورة شخصية' },
  OTHER: { type: 'SUPPORTING_DOCUMENT', en: 'Other supporting document', ar: 'مستند داعم آخر' },
}

async function sendContent(id: string, file: File) {
  const s = await fresh('customer')
  if (!s) throw new Error('signed out')
  const res = await fetch(`/v1/documents/${id}/content`, {
    method: 'POST', body: file,
    headers: { Authorization: `Bearer ${s.accessToken}`, 'X-Channel': 'PORTAL', 'Content-Type': 'application/octet-stream' },
  })
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new ApiError(res.status, body)
  return body as Doc
}

export default function Documents({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const me = load('customer')?.claims
  const [types, setTypes] = useState<DocType[] | null>(null)
  const [docs, setDocs] = useState<Doc[] | null>(null)
  const [kind, setKind] = useState<Kind>('EID')
  const [idNo, setIdNo] = useState('')
  const [licNo, setLicNo] = useState('')
  const [title, setTitle] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [err, setErr] = useState('')

  const reload = useCallback(() => {
    if (!me?.sub) return
    call<{ items: Doc[] }>('customer', 'GET', `/v1/documents?ownerType=CUSTOMER&ownerId=${me.sub}`, undefined, lang)
      .then((r) => setDocs(r.items)).catch(() => setDocs([]))
  }, [lang, me?.sub])
  useEffect(() => {
    call<{ items: DocType[] }>('customer', 'GET', '/v1/document-types', undefined, lang).then((r) => setTypes(r.items)).catch(() => setTypes([]))
    reload()
  }, [lang, reload])

  const t = types?.find((x) => x.code === KINDS[kind].type)
  const rules: { title: string; items: Rule[] }[] = useMemo(() => {
    const out: { title: string; items: Rule[] }[] = []
    if (kind === 'EID') {
      const own = digitsOf(idNo) === String(me?.national_id ?? '')
      out.push({ title: ar ? 'رقم الهوية' : 'Emirates ID number', items: [...emiratesIdRules(idNo),
        { ok: own, en: 'Matches your own Emirates ID (you can only upload your own)', ar: 'يطابق هويتك (يمكنك رفع هويتك فقط)' }] })
    }
    if (kind === 'LICENCE') out.push({ title: ar ? 'رقم الرخصة' : 'Licence number', items: licenceNumberRules(licNo) })
    if (kind === 'OTHER') out.push({ title: ar ? 'العنوان' : 'Title', items: [{ ok: title.trim().length >= 3 && title.length <= 200, en: '3 to 200 characters', ar: 'من 3 إلى 200 حرف' }] })
    if (t) out.push({ title: ar ? 'الملف' : 'File', items: fileRules(file, t.mediaTypes, t.maxBytes) })
    return out
  }, [kind, idNo, licNo, title, file, t, me?.national_id, ar])
  const ready = !!t && rules.every((g) => allOk(g.items))

  const docTitle = () => kind === 'EID' ? `Emirates ID ${digitsOf(idNo)}` : kind === 'LICENCE' ? `Driving licence ${licNo}` : kind === 'PHOTO' ? 'Personal photo' : title.trim()

  const upload = async () => {
    if (!me?.sub || !file || !t) return
    setBusy('upload'); setErr('')
    try {
      const d = await call<Doc>('customer', 'POST', '/v1/documents', { type: t.code, ownerType: 'CUSTOMER', ownerId: me.sub, title: docTitle() }, lang)
      const done = await sendContent(d.id, file)
      notify(done.status === 'AVAILABLE' ? (ar ? 'تم الرفع والفحص: الملف سليم' : 'Uploaded and scanned: the file is clean') : `${done.status}`)
      setFile(null); setIdNo(''); setLicNo(''); setTitle('')
      const picker = document.getElementById('doc-file') as HTMLInputElement | null
      if (picker) picker.value = ''
      reload()
    } catch (e) {
      const code = e instanceof ApiError ? String(e.problem.code ?? '') : ''
      setErr(reasonText(code, (e as Error).message, lang))
      reload()
    } finally { setBusy(null) }
  }

  const download = async (d: Doc) => {
    setBusy(d.id)
    try {
      const s = await fresh('customer')
      const res = await fetch(`/v1/documents/${d.id}/content`, { headers: { Authorization: `Bearer ${s?.accessToken}`, 'X-Channel': 'PORTAL' } })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const a = document.createElement('a')
      a.href = URL.createObjectURL(await res.blob())
      a.download = `${(d.title || d.type).replace(/[^\w.-]+/g, '_')}.${(d.mediaType ?? 'application/pdf').split('/')[1]}`
      a.click(); URL.revokeObjectURL(a.href)
    } catch (e) { notify((e as Error).message, true) } finally { setBusy(null) }
  }

  return (
    <div className="grid g-2" style={{ gridAutoFlow: 'row dense' }}>
      <Card title={ar ? 'رفع مستند' : 'Upload a document'} icon={<FileUp size={16} />} right={<span className="pill">CC-080 · CC-081</span>}>
        <label className="field"><span>{ar ? 'نوع المستند' : 'Document'}</span>
          <select value={kind} onChange={(e) => { setKind(e.target.value as Kind); setErr('') }}>
            {(Object.keys(KINDS) as Kind[]).map((k) => <option key={k} value={k}>{ar ? KINDS[k].ar : KINDS[k].en}</option>)}
          </select></label>
        {kind === 'EID' && <label className="field"><span>{ar ? 'رقم الهوية الإماراتية' : 'Emirates ID number'}</span>
          <input value={idNo} onChange={(e) => setIdNo(e.target.value)} placeholder="784-1990-1234567-1" inputMode="numeric" dir="ltr" /></label>}
        {kind === 'LICENCE' && <label className="field"><span>{ar ? 'رقم رخصة القيادة' : 'Driving licence number'}</span>
          <input value={licNo} onChange={(e) => setLicNo(e.target.value.trim())} placeholder="1234567" dir="ltr" /></label>}
        {kind === 'OTHER' && <label className="field"><span>{ar ? 'العنوان' : 'Title'}</span>
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} /></label>}
        <label className="field"><span>{ar ? 'الملف' : 'File'}</span>
          <input id="doc-file" type="file" accept={t?.mediaTypes.join(',')} onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
        <div className="rules-grid">{types === null ? <Skeleton lines={2} /> : rules.map((g) => <RuleList key={g.title} title={g.title} rules={g.items} lang={lang} />)}</div>
        {kind === 'LICENCE' && <p className="muted" style={{ fontSize: 12 }}>{ar ? 'بادئة الرقم وطوله لكل إمارة تحددها نطاقات أرقام الرخص لدى الهيئة.' : 'Each emirate’s prefix and length come from the Authority’s licence-number ranges.'}</p>}
        {err && <p role="alert" style={{ color: 'var(--red)', fontSize: 13 }}>{err}</p>}
        <button className="btn primary" disabled={!ready || busy === 'upload'} onClick={upload}>
          <FileUp size={16} /> {busy === 'upload' ? (ar ? 'جارٍ الرفع والفحص…' : 'Uploading and scanning…') : (ar ? 'رفع' : 'Upload')}</button>
      </Card>
      <Card title={ar ? 'مستنداتي' : 'My documents'} icon={<FileCheck2 size={16} />} right={<span className="pill">{docs?.length ?? 0}</span>}>
        {docs === null ? <Skeleton /> : docs.length === 0 ? <div className="empty">{ar ? 'لا مستندات بعد' : 'No documents yet'}</div> : (
          <div className="list">{docs.map((d) => (
            <div className="row" key={d.id}>
              <div className="grow">
                <div className="title">{d.title || d.type}</div>
                <div className="meta"><span>{d.type}</span><span>{when(d.createdAt, lang)}</span>{d.size ? <span>{Math.max(1, Math.round(d.size / 1024))} KB</span> : null}
                  {d.sha256 && <span className="mono" title={`SHA-256 ${d.sha256}`}>#{d.sha256.slice(0, 10)}</span>}</div>
              </div>
              <StatusPill s={d.status} />
              {d.status === 'AVAILABLE' && <button className="btn ghost" onClick={() => download(d)} disabled={busy === d.id} aria-label={`Download ${d.title ?? d.type}`}><Download size={15} /></button>}
            </div>))}</div>
        )}
        <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}><ShieldCheck size={13} style={{ verticalAlign: -2 }} /> {ar
          ? 'كل ملف يُفحص من البرمجيات الضارة ويُحفظ مع بصمته. المستندات سجلات رسمية ولا تُحذف من البوابة.'
          : 'Every file is scanned for malware and stored with its fingerprint. Documents are official records and cannot be deleted from the portal.'}</p>
      </Card>
    </div>
  )
}
