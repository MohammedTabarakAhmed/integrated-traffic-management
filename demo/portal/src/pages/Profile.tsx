import { useEffect, useState, type ReactNode } from 'react'
import { BellRing, CalendarDays, FileText, IdCard, Mail, MapPin, Phone, UserRound } from 'lucide-react'
import { call, when } from '../api'
import { Card, Skeleton, StatusPill } from '../components'
import { useI18n } from '../i18n'
import { nameOf } from '../names'

// My profile (DL-001, DL-005): what the traffic file holds about the signed-in citizen, read from /v1/me/traffic-file.
// Sensitive identity numbers come back masked; contact details are changed on Contact & alerts (with a one-time password).

type File = {
  fileNumber: string; status: string; kind: string; category: string; type: string; regionOpened: string; identitySource: string
  identity?: { documentType: string; nationalIdNo?: string | null; name?: { givenEn?: string | null; familyEn?: string | null; givenNl?: string | null; familyNl?: string | null }; nationality?: string; birthDate?: string; gender?: string } | null
  profile?: { mobile?: string | null; email?: string | null; address?: string | null; languages?: string[] | null } | null
  opened: { at: string; channel: string }; lastChange: { at: string; channel: string }
}

function Item({ icon, label, children }: { icon: ReactNode; label: string; children: ReactNode }) {
  return <div className="pf-item"><span className="pf-ico">{icon}</span><div><small>{label}</small><div>{children}</div></div></div>
}

export default function Profile({ go }: { go: (r: string) => void }) {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [f, setF] = useState<File | null>(null)
  const [err, setErr] = useState('')
  useEffect(() => { call<File>('customer', 'GET', '/v1/me/traffic-file', undefined, lang).then(setF).catch((e) => setErr(e.message)) }, [lang])

  const none = <span className="muted">{ar ? 'غير مسجل' : 'Not on file'}</span>
  if (err) return <div className="card empty">{err}</div>
  if (!f) return <div className="grid g-2"><section className="card"><Skeleton lines={5} /></section></div>
  const n = f.identity?.name
  const full = ar ? [n?.givenNl, n?.familyNl].filter(Boolean).join(' ') : [n?.givenEn, n?.familyEn].filter(Boolean).join(' ')
  return (
    <div className="grid g-2">
      <Card title={ar ? 'بياناتي' : 'About me'} icon={<UserRound size={16} />}>
        <div className="pf-head">
          <div className="pf-avatar">{(full || 'C').slice(0, 1)}</div>
          <div><b style={{ fontSize: 18 }}>{full || (ar ? 'متعامل' : 'Citizen')}</b><div className="muted" style={{ fontSize: 13 }}>{ar ? 'رقم الهوية' : 'National ID'} {f.identity?.nationalIdNo ?? '•••• 5671'}</div></div>
        </div>
        <div className="pf-grid">
          <Item icon={<IdCard size={15} />} label={ar ? 'الجنسية' : 'Nationality'}>{f.identity?.nationality ?? none}</Item>
          <Item icon={<CalendarDays size={15} />} label={ar ? 'تاريخ الميلاد' : 'Date of birth'}>{f.identity?.birthDate ?? none}</Item>
          <Item icon={<UserRound size={15} />} label={ar ? 'الجنس' : 'Gender'}>{f.identity?.gender ? nameOf(f.identity.gender, lang) : none}</Item>
          <Item icon={<FileText size={15} />} label={ar ? 'مصدر البيانات' : 'Identity from'}>{f.identitySource === 'REGISTER' ? (ar ? 'سجل الهوية الوطني' : 'National identity register') : (ar ? 'وثيقة' : 'A document')}</Item>
        </div>
      </Card>
      <Card title={ar ? 'الملف المروري' : 'Traffic file'} icon={<FileText size={16} />} right={<StatusPill s={f.status} />}>
        <div className="pf-grid">
          <Item icon={<FileText size={15} />} label={ar ? 'رقم الملف' : 'File number'}><span className="mono">{f.fileNumber}</span></Item>
          <Item icon={<MapPin size={15} />} label={ar ? 'فُتح في' : 'Opened in'}>{nameOf(f.regionOpened, lang)}</Item>
          <Item icon={<UserRound size={15} />} label={ar ? 'الفئة' : 'Category'}>{nameOf(f.category, lang)} · {nameOf(f.type, lang)}</Item>
          <Item icon={<CalendarDays size={15} />} label={ar ? 'تاريخ الفتح' : 'Opened on'}>{when(f.opened.at, lang)}</Item>
          <Item icon={<CalendarDays size={15} />} label={ar ? 'آخر تحديث' : 'Last updated'}>{when(f.lastChange.at, lang)}</Item>
        </div>
      </Card>
      <Card className="span-2" title={ar ? 'بيانات التواصل' : 'Contact details'} icon={<Phone size={16} />}
        right={<button className="btn" onClick={() => go('settings')}><BellRing size={15} /> {ar ? 'تغيير' : 'Change'}</button>}>
        <div className="pf-grid three">
          <Item icon={<Phone size={15} />} label={ar ? 'الهاتف المتحرك' : 'Mobile'}>{f.profile?.mobile ?? none}</Item>
          <Item icon={<Mail size={15} />} label={ar ? 'البريد الإلكتروني' : 'Email'}>{f.profile?.email ?? none}</Item>
          <Item icon={<MapPin size={15} />} label={ar ? 'العنوان' : 'Address'}>{f.profile?.address ?? none}</Item>
        </div>
        <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{ar ? 'لحماية حسابك، يطلب تغيير بيانات التواصل رمزًا لمرة واحدة.' : 'To protect your account, changing contact details asks for a one-time password.'}</p>
      </Card>
    </div>
  )
}
