import { CheckCircle2, Circle } from 'lucide-react'

// Input rules shown while the user types (like password rules), so a form explains itself before it is sent.
// The server still checks everything; these only mirror what it enforces, plus the UAE formats the owner asked for
// (2026-10-14). Nothing here is a business rule of its own: the national ID format stays configurable server-side
// (Q-057) and licence numbers follow the Authority's per-region ranges (Q-022).

export type Rule = { ok: boolean; en: string; ar: string }

export function RuleList({ rules, lang, title }: { rules: Rule[]; lang: string; title?: string }) {
  const ar = lang === 'ar'
  return (
    <ul className="rules" aria-label={title ?? (ar ? 'شروط الإدخال' : 'Input rules')}>
      {title && <li className="rules-title">{title}</li>}
      {rules.map((r) => (
        <li key={r.en} className={r.ok ? 'ok' : ''}>
          {r.ok ? <CheckCircle2 size={14} /> : <Circle size={14} />} {ar ? r.ar : r.en}
        </li>
      ))}
    </ul>
  )
}

export const allOk = (rules: Rule[]) => rules.every((r) => r.ok)
export const digitsOf = (v: string) => v.replace(/[\s-]/g, '')

/** UAE Emirates ID: 784-YYYY-NNNNNNN-C, 15 digits, 784 first, then a plausible birth year. */
export function emiratesIdRules(v: string): Rule[] {
  const d = digitsOf(v)
  const year = Number(d.slice(3, 7))
  return [
    { ok: d.length > 0 && /^\d+$/.test(d), en: 'Digits only (dashes and spaces are fine)', ar: 'أرقام فقط (الشرطات والمسافات مقبولة)' },
    { ok: d.length === 15, en: 'Exactly 15 digits', ar: '15 رقمًا بالضبط' },
    { ok: d.startsWith('784'), en: 'Starts with 784 (UAE)', ar: 'يبدأ بـ 784 (الإمارات)' },
    { ok: d.length >= 7 && year >= 1900 && year <= new Date().getFullYear(), en: 'Digits 4-7 are the birth year', ar: 'الأرقام 4-7 سنة الميلاد' },
  ]
}

/** UAE mobile: +971 5X XXX XXXX. */
export function uaeMobileRules(v: string): Rule[] {
  const d = v.replace(/[\s-]/g, '')
  return [
    { ok: d.startsWith('+971'), en: 'Starts with +971', ar: 'يبدأ بـ +971' },
    { ok: /^\+9715\d*$/.test(d), en: 'A mobile number: 5 after +971', ar: 'رقم متحرك: 5 بعد +971' },
    { ok: /^\+9715\d{8}$/.test(d), en: '9 digits after +971', ar: '9 أرقام بعد +971' },
  ]
}

export function emailRules(v: string): Rule[] {
  return [
    { ok: /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(v), en: 'A valid email address (name@domain.ae)', ar: 'بريد إلكتروني صالح (name@domain.ae)' },
    { ok: v.length <= 320, en: 'At most 320 characters', ar: '320 حرفًا كحد أقصى' },
  ]
}

/** Passport number as traffic-file-service accepts it. */
export function passportRules(v: string): Rule[] {
  return [
    { ok: /^[A-Z0-9]*$/.test(v) && v.length > 0, en: 'Capital letters and digits only', ar: 'حروف كبيرة وأرقام فقط' },
    { ok: v.length >= 5 && v.length <= 20, en: '5 to 20 characters', ar: 'من 5 إلى 20 حرفًا' },
  ]
}

/** Driving licence number as the licensing service accepts it; the per-emirate prefix and length come from the
 *  Authority's number ranges (Q-022), so they are not checked here. */
export function licenceNumberRules(v: string): Rule[] {
  return [
    { ok: v.length > 0 && /^[A-Za-z0-9/-]+$/.test(v), en: 'Letters, digits, - and / only', ar: 'حروف وأرقام و - و / فقط' },
    { ok: v.length >= 3 && v.length <= 20, en: '3 to 20 characters', ar: 'من 3 إلى 20 حرفًا' },
    { ok: /\d/.test(v), en: 'Contains the licence digits', ar: 'يحتوي على أرقام الرخصة' },
  ]
}

/** A file against its document type's rules (from GET /v1/document-types). */
export function fileRules(f: File | null, mediaTypes: string[], maxBytes: number): Rule[] {
  const mb = (n: number) => `${Math.round(n / 1048576)} MB`
  const kinds = mediaTypes.map((m) => m.split('/')[1].toUpperCase()).join(', ')
  return [
    { ok: !!f, en: 'A file is chosen', ar: 'تم اختيار ملف' },
    { ok: !!f && mediaTypes.includes(f.type), en: `File type: ${kinds}`, ar: `نوع الملف: ${kinds}` },
    { ok: !!f && f.size > 0 && f.size <= maxBytes, en: `Not empty, at most ${mb(maxBytes)}`, ar: `غير فارغ، ${mb(maxBytes)} كحد أقصى` },
  ]
}

/** Server refusal codes in plain words (traffic files, documents, transactions). */
export const REASONS: Record<string, [string, string]> = {
  REGISTER_PERSON_NOT_FOUND: ['This Emirates ID is not in the national identity register.', 'رقم الهوية غير موجود في سجل الهوية الوطني.'],
  PERSON_DECEASED: ['The register reports this person as deceased; no file can be opened.', 'السجل يفيد بوفاة الشخص؛ لا يمكن فتح ملف.'],
  PERSON_BANNED: ['This person is on the ban list; no file can be opened.', 'الشخص على قائمة الحظر؛ لا يمكن فتح ملف.'],
  FILE_ALREADY_EXISTS: ['This document already has a traffic file (one file per person).', 'لهذه الوثيقة ملف مروري بالفعل (ملف واحد لكل شخص).'],
  FIELD_INVALID: ['A field has the wrong format.', 'أحد الحقول بصيغة غير صحيحة.'],
  BIRTH_DATE_IN_FUTURE: ['The birth date cannot be in the future.', 'لا يمكن أن يكون تاريخ الميلاد في المستقبل.'],
  VISITOR_IDENTITY_REQUIRED: ['A passport holder needs their name, nationality, birth date and gender.', 'حامل الجواز يحتاج الاسم والجنسية وتاريخ الميلاد والجنس.'],
  REGISTER_UNAVAILABLE: ['The identity register is not answering; try again shortly.', 'سجل الهوية لا يستجيب؛ حاول بعد قليل.'],
  SCOPE_EXCEEDED: ['Your role does not cover this region.', 'صلاحيتك لا تشمل هذه المنطقة.'],
  MALWARE_FOUND: ['The file was rejected by the malware scan.', 'رُفض الملف من فحص البرمجيات الضارة.'],
  FILE_TYPE_NOT_ALLOWED: ['This file type is not allowed for this document.', 'نوع الملف غير مسموح لهذه الوثيقة.'],
  FILE_TOO_LARGE: ['The file is larger than allowed.', 'الملف أكبر من المسموح.'],
  FILE_EMPTY: ['The file is empty.', 'الملف فارغ.'],
  ILLEGAL_TRANSITION: ['This application can no longer be cancelled (only Draft or Pending payment).', 'لا يمكن إلغاء هذه المعاملة الآن (المسودة أو بانتظار الدفع فقط).'],
}
export const reasonText = (code: string | undefined, fallback: string, lang: string) => {
  const r = code ? REASONS[code] : undefined
  return r ? `${lang === 'ar' ? r[1] : r[0]} (${code})` : fallback
}
