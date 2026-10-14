import { useEffect, useState } from 'react'
import { KeyRound, Scale, UserCheck } from 'lucide-react'
import { call } from '../api'
import { Card, Skeleton } from '../components'
import { useI18n } from '../i18n'

// Who may do what (CC-001, CC-002, CC-003, CC-005): the effective authorisation iam-service computes right now for
// the two demo officers, side by side: roles, permissions, data scopes (region, centre, module) and approval limits.
// Grants come from iam, not from Keycloak (ADR-022); the same data drives every service's 403.

type Grant = { roleCode: string; permissions: string[]; scopes: { regions: string[]; centres: string[]; modules: string[] }; validTo?: string | null
  approvalLimits: { action?: string; code?: string; maxMinor?: number; limitMinor?: number; [k: string]: unknown }[] }
type Auth = { subjectId: string; evaluatedAt: string; grants: Grant[] }

export default function Access() {
  const { lang } = useI18n()
  const ar = lang === 'ar'
  const [auth, setAuth] = useState<{ maker: Auth | null; checker: Auth | null }>({ maker: null, checker: null })
  const [q, setQ] = useState('')

  useEffect(() => {
    call<Auth>('staff', 'GET', '/v1/me/authorisation', undefined, lang).then((a) => setAuth((x) => ({ ...x, maker: a }))).catch(() => {})
    call<Auth>('checker', 'GET', '/v1/me/authorisation', undefined, lang).then((a) => setAuth((x) => ({ ...x, checker: a }))).catch(() => {})
  }, [lang])

  const perms = (a: Auth | null) => new Set((a?.grants ?? []).flatMap((g) => g.permissions))
  const pm = perms(auth.maker), pc = perms(auth.checker)
  const both = [...new Set([...pm, ...pc])].filter((p) => !q || p.includes(q.toLowerCase())).sort()
  const limits = (a: Auth | null) => (a?.grants ?? []).flatMap((g) => g.approvalLimits.map((l) => ({ role: g.roleCode, ...l })))

  const column = (name: string, a: Auth | null, desc: [string, string]) => (
    <Card title={name} icon={<UserCheck size={16} />} right={a ? <span className="pill">{a.grants.length} {ar ? 'دور' : 'roles'} · {perms(a).size} {ar ? 'صلاحية' : 'permissions'}</span> : null}>
      <p className="muted" style={{ marginTop: 0, fontSize: 13 }}>{ar ? desc[1] : desc[0]}</p>
      {!a ? <Skeleton /> : (
        <>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5 }}>{a.grants.map((g) => (
            <span key={g.roleCode} className="pill" title={`${g.permissions.join(', ')}\nregions ${g.scopes.regions.join(',')} · modules ${g.scopes.modules.join(',')}`}>{g.roleCode}</span>))}</div>
          {limits(a).length > 0 && (
            <div style={{ marginTop: 12 }}><b style={{ fontSize: 13 }}><Scale size={13} /> {ar ? 'حدود الاعتماد' : 'Approval limits'} (CC-003)</b>
              <ul className="rules">{limits(a).map((l, i) => <li key={i} className="ok">{l.role}: {Object.entries(l).filter(([k]) => k !== 'role').map(([k, v]) => `${k} ${String(v)}`).join(' · ')}</li>)}</ul></div>
          )}
        </>
      )}
    </Card>
  )

  return (
    <>
      <div className="grid g-2">
        {column('dev.maker', auth.maker, ['Officer: records violations and proposes changes.', 'موظف: يسجّل المخالفات ويقترح التغييرات.'])}
        {column('dev.checker', auth.checker, ['Supervisor: approves what others propose, decides cases.', 'مشرف: يعتمد ما يقترحه غيره ويبت في الحالات.'])}
      </div>
      <Card title={ar ? 'من يملك أي صلاحية' : 'Who holds which permission'} icon={<KeyRound size={16} />} className="qa-gap" right={<span className="pill">CC-001 · CC-002 · CC-005</span>}>
        <div className="req-filters"><input placeholder={ar ? 'تصفية الصلاحيات (مثل catalogue)' : 'Filter permissions (e.g. catalogue)'} value={q} onChange={(e) => setQ(e.target.value)} aria-label="Filter permissions" /></div>
        <div className="table-wrap" style={{ maxHeight: 420, overflow: 'auto' }}><table className="tbl">
          <thead><tr><th>{ar ? 'الصلاحية' : 'Permission'}</th><th>dev.maker</th><th>dev.checker</th></tr></thead>
          <tbody>{both.map((p) => (
            <tr key={p}><td className="mono">{p}</td><td>{pm.has(p) ? '✓' : ''}</td><td>{pc.has(p) ? '✓' : ''}</td></tr>))}</tbody>
        </table></div>
        <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>{ar
          ? 'تُحسب الصلاحيات الآن من خدمة الصلاحيات (iam)؛ وهي نفسها التي تقرر الرفض 403 في كل خدمة. المنشئ والمدقق شخصان مختلفان، ولا يعتمد أحد ما اقترحه بنفسه.'
          : 'Computed now by iam-service; the same grants decide every 403 in every service. Maker and checker are different people, and nobody approves their own proposal.'}</p>
      </Card>
    </>
  )
}
