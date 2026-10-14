import { useMemo, useState } from 'react'
import { CheckCircle2, IdCard, ShieldAlert, UserPlus } from 'lucide-react'
import { ApiError, call } from '../api'
import { Card } from '../components'
import { useI18n } from '../i18n'
import { allOk, digitsOf, emailRules, emiratesIdRules, passportRules, reasonText, RuleList, uaeMobileRules } from '../rules'

// Open a traffic file (DL-001, DL-002, DL-013) at the counter, as dev.checker (TRAFFIC_FILE_OFFICER). Not everyone
// can get one: traffic-file-service looks the Emirates ID up in the national identity register and refuses unknown,
// deceased or banned people and anyone who already has a file; a visitor opens one with a passport instead.

type Doc = 'NATIONAL_ID' | 'PASSPORT'
type FileT = { fileNumber: string; status: string; identity?: { name?: Record<string, string | null> } | null }

export default function NewFile({ notify }: { notify: (t: string, bad?: boolean) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [doc, setDoc] = useState<Doc>('NATIONAL_ID')
  const [eid, setEid] = useState('')
  const [pp, setPp] = useState({ no: '', country: '', given: '', family: '', birth: '', gender: 'MALE' })
  const [mobile, setMobile] = useState('+971')
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [out, setOut] = useState<{ ok: boolean; text: string } | null>(null)

  const today = new Date().toISOString().slice(0, 10)
  const groups = useMemo(() => {
    const g = doc === 'NATIONAL_ID'
      ? [{ title: ar ? 'رقم الهوية الإماراتية' : 'Emirates ID', items: emiratesIdRules(eid) }]
      : [{ title: ar ? 'جواز السفر' : 'Passport', items: [...passportRules(pp.no),
          { ok: /^[A-Z]{3}$/.test(pp.country), en: 'Country: 3 capital letters (e.g. IND, GBR)', ar: 'الدولة: 3 حروف كبيرة (مثل IND)' }] },
        { title: ar ? 'بيانات الزائر' : 'Visitor details', items: [
          { ok: pp.given.trim().length > 0 && pp.family.trim().length > 0, en: 'Given and family name', ar: 'الاسم الأول واسم العائلة' },
          { ok: !!pp.birth && pp.birth <= today, en: 'Birth date, not in the future', ar: 'تاريخ ميلاد ليس في المستقبل' }] }]
    if (mobile !== '+971' && mobile !== '') g.push({ title: ar ? 'الجوال' : 'Mobile', items: uaeMobileRules(mobile) })
    if (email) g.push({ title: ar ? 'البريد' : 'Email', items: emailRules(email) })
    return g
  }, [doc, eid, pp, mobile, email, today, ar])
  const ready = groups.every((x) => allOk(x.items))

  const open = async () => {
    setBusy(true); setOut(null)
    const profile: Record<string, unknown> = {}
    if (mobile !== '+971' && mobile) profile.mobile = mobile.replace(/[\s-]/g, '')
    if (email) profile.email = email
    const body = doc === 'NATIONAL_ID'
      ? { document: { type: 'NATIONAL_ID', nationalIdNo: digitsOf(eid) }, category: 'INDIVIDUAL', type: 'CITIZEN', regionOpened: 'DXB', profile }
      : { document: { type: 'PASSPORT', passportNo: pp.no, passportCountry: pp.country }, category: 'INDIVIDUAL', type: 'VISITOR', regionOpened: 'DXB', profile,
          visitorIdentity: { name: { givenEn: pp.given.trim(), familyEn: pp.family.trim() }, nationality: pp.country, birthDate: pp.birth, gender: pp.gender } }
    try {
      const f = await call<FileT>('checker', 'POST', '/v1/traffic-files/individuals', body, lang)
      const text = `${ar ? 'فُتح الملف المروري' : 'Traffic file opened'} ${f.fileNumber} (${f.status})`
      setOut({ ok: true, text }); notify(text)
    } catch (e) {
      setOut({ ok: false, text: reasonText(e instanceof ApiError ? String(e.problem.code ?? '') : '', (e as Error).message, lang) })
    } finally { setBusy(false) }
  }

  const criteria: [string, string][] = [
    ['In the national identity register (Emirates ID), or a visitor with a passport', 'مسجّل في سجل الهوية الوطني (الهوية الإماراتية)، أو زائر بجواز سفر'],
    ['Alive according to the register', 'على قيد الحياة حسب السجل'],
    ['Not on the ban list', 'ليس على قائمة الحظر'],
    ['One file per person: the document must not already have a file', 'ملف واحد لكل شخص: لا يكون للوثيقة ملف مسبق'],
  ]

  return (
    <div className="grid g-2" style={{ gridAutoFlow: 'row dense' }}>
      <Card title={ar ? 'فتح ملف مروري' : 'Open a traffic file'} icon={<UserPlus size={16} />} right={<span className="pill">DL-001 · DL-002 · DL-013</span>}>
        <div className="seg" role="tablist" style={{ marginBottom: 12 }}>
          {(['NATIONAL_ID', 'PASSPORT'] as Doc[]).map((d) => (
            <button key={d} role="tab" aria-selected={doc === d} className={doc === d ? 'on' : ''} onClick={() => { setDoc(d); setOut(null) }}>
              {d === 'NATIONAL_ID' ? (ar ? 'هوية إماراتية' : 'Emirates ID') : (ar ? 'زائر (جواز سفر)' : 'Visitor (passport)')}</button>))}
        </div>
        {doc === 'NATIONAL_ID' ? (
          <label className="field"><span>{ar ? 'رقم الهوية الإماراتية' : 'Emirates ID number'}</span>
            <input value={eid} onChange={(e) => setEid(e.target.value)} placeholder="784-1990-1234567-1" inputMode="numeric" dir="ltr" /></label>
        ) : (
          <>
            <div className="pf-grid">
              <label className="field"><span>{ar ? 'رقم الجواز' : 'Passport number'}</span>
                <input value={pp.no} onChange={(e) => setPp({ ...pp, no: e.target.value.toUpperCase() })} dir="ltr" /></label>
              <label className="field"><span>{ar ? 'الدولة المصدرة' : 'Issuing country'}</span>
                <input value={pp.country} onChange={(e) => setPp({ ...pp, country: e.target.value.toUpperCase() })} maxLength={3} placeholder="IND" dir="ltr" /></label>
              <label className="field"><span>{ar ? 'الاسم الأول' : 'Given name'}</span>
                <input value={pp.given} onChange={(e) => setPp({ ...pp, given: e.target.value })} /></label>
              <label className="field"><span>{ar ? 'اسم العائلة' : 'Family name'}</span>
                <input value={pp.family} onChange={(e) => setPp({ ...pp, family: e.target.value })} /></label>
              <label className="field"><span>{ar ? 'تاريخ الميلاد' : 'Birth date'}</span>
                <input type="date" value={pp.birth} max={today} onChange={(e) => setPp({ ...pp, birth: e.target.value })} /></label>
              <label className="field"><span>{ar ? 'الجنس' : 'Gender'}</span>
                <select value={pp.gender} onChange={(e) => setPp({ ...pp, gender: e.target.value })}>
                  <option value="MALE">{ar ? 'ذكر' : 'Male'}</option><option value="FEMALE">{ar ? 'أنثى' : 'Female'}</option></select></label>
            </div>
          </>
        )}
        <div className="pf-grid">
          <label className="field"><span>{ar ? 'الجوال (اختياري)' : 'Mobile (optional)'}</span>
            <input value={mobile} onChange={(e) => setMobile(e.target.value)} placeholder="+971 50 123 4567" dir="ltr" /></label>
          <label className="field"><span>{ar ? 'البريد (اختياري)' : 'Email (optional)'}</span>
            <input value={email} onChange={(e) => setEmail(e.target.value.trim())} type="email" dir="ltr" /></label>
        </div>
        <div className="rules-grid">{groups.map((g) => <RuleList key={g.title} title={g.title} rules={g.items} lang={lang} />)}</div>
        {out && <div className={`banner ${out.ok ? 'info' : ''}`} role="status" style={{ margin: '10px 0' }}>
          {out.ok ? <CheckCircle2 color="var(--green)" /> : <ShieldAlert color="var(--red)" />}<div>{out.text}</div></div>}
        <button className="btn primary" disabled={!ready || busy} onClick={open}><IdCard size={16} /> {busy ? (ar ? 'جارٍ التحقق من السجل…' : 'Checking the register…') : (ar ? 'فتح الملف' : 'Open file')}</button>
      </Card>
      <Card title={ar ? 'من يحق له ملف مروري؟' : 'Who can get a traffic file?'} icon={<ShieldAlert size={16} />}>
        <ul className="rules">{criteria.map(([en, a]) => <li key={en} className="ok"><CheckCircle2 size={14} /> {ar ? a : en}</li>)}</ul>
        <p className="muted" style={{ fontSize: 12.5 }}>{ar
          ? 'تتحقق خدمة الملفات المرورية من هذه الشروط عند الإرسال، والسبب يظهر هنا. جرّب: 784197012345673 (متوفى في السجل)، 784199012345671 (لديه ملف)، أو أي رقم آخر (غير موجود في السجل).'
          : 'traffic-file-service checks these when you submit, and its reason is shown here. Try 784197012345673 (deceased in the register), 784199012345671 (already has a file), or any other number (not in the register).'}</p>
      </Card>
    </div>
  )
}
