// Readable names for codes the services return, so people see "Driving licence renewal" rather than DEMO_RENEWAL.
// Anything without a name falls back to a sentence-case version of the code.

type Pair = [en: string, ar: string]

const NAMES: Record<string, Pair> = {
  // services
  DEMO_RENEWAL: ['Driving licence renewal', 'تجديد رخصة القيادة'],
  DEMO_ADDRESS: ['Address update', 'تحديث العنوان'],
  DEMO_CARD: ['Licence card replacement', 'بدل فاقد لبطاقة الرخصة'],
  DEMO_EYE_TEST: ['Eye test', 'فحص النظر'],
  VEH_FIRST_REG: ['First vehicle registration', 'التسجيل الأول للمركبة'],
  // violations
  DEMO_SPEED: ['Speeding more than 20 km/h over the limit', 'تجاوز السرعة بأكثر من 20 كم/س'],
  DEMO_PHONE: ['Using a phone while driving', 'استخدام الهاتف أثناء القيادة'],
  DEMO_PARK: ['Parking in a prohibited place', 'الوقوف في مكان غير مخصص'],
  DEMO_REDLIGHT: ['Running a red light', 'تجاوز الإشارة الحمراء'],
  DEMO_OVERLOAD: ['Truck over the permitted load', 'حمولة زائدة على شاحنة'],
  // objection reasons
  NOT_THE_DRIVER: ['I was not driving', 'لم أكن أقود المركبة'],
  WRONG_PLATE: ['Wrong plate read', 'قراءة خاطئة للوحة'],
  SIGN_NOT_VISIBLE: ['Sign or signal not visible', 'الإشارة غير واضحة'],
  EMERGENCY: ['Medical or other emergency', 'حالة طارئة'],
  // holds, steps, modules
  LICENCE_SUSPENSION: ['Licence suspension', 'إيقاف الرخصة'],
  UNPAID_FINE: ['Unpaid fine', 'غرامة غير مدفوعة'],
  REVIEW: ['Review', 'مراجعة'],
  VIOLATIONS: ['Violations', 'المخالفات'],
  LICENSING: ['Licensing', 'الترخيص'],
  TRAFFIC_FILE: ['Traffic file', 'الملف المروري'],
  DEMO_DXB_HQ: ['Dubai main centre', 'مركز دبي الرئيسي'],
  // regions and file categories
  ARE: ['United Arab Emirates', 'الإمارات العربية المتحدة'],
  OMN: ['Oman', 'عُمان'],
  SAU: ['Saudi Arabia', 'السعودية'],
  DXB: ['Dubai', 'دبي'],
  AUH: ['Abu Dhabi', 'أبوظبي'],
  SHJ: ['Sharjah', 'الشارقة'],
  CITIZEN: ['Citizen', 'مواطن'],
  RESIDENT: ['Resident', 'مقيم'],
  INDIVIDUAL: ['Individual', 'فرد'],
  COMPANY: ['Company', 'شركة'],
  M: ['Male', 'ذكر'],
  F: ['Female', 'أنثى'],
}

/** "LICENCE_SUSPENSION" -> "Licence suspension"; "DEMO_X" loses its prefix. */
export function sentence(code: string): string {
  const words = code.replace(/^DEMO_/, '').replace(/[_-]+/g, ' ').trim().toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function nameOf(code: string | null | undefined, lang: string): string {
  if (!code) return '—'
  const n = NAMES[code]
  return n ? n[lang === 'ar' ? 1 : 0] : sentence(code)
}
