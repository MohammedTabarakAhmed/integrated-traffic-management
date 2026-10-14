import { useMemo, useState } from 'react'
import { CheckCircle2, LayoutDashboard, RefreshCcw, ShieldCheck } from 'lucide-react'
import { money } from '../api'
import { useI18n } from '../i18n'

// Back from the (mock) gateway: the callback has been processed and the receipt issued (FN-004).
export default function Receipt({ go }: { go: (r: string) => void }) {
  const { t, lang } = useI18n()
  const q = useMemo(() => new URLSearchParams(location.hash.split('?')[1] ?? ''), [])
  const payment = q.get('payment') ?? ''
  const [dup, setDup] = useState<{ duplicate: boolean; receiptNumber?: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const colours = ['#22d3ee', '#6366f1', '#a855f7', '#34d399', '#fbbf24']

  const [err, setErr] = useState('')
  // The mock gateway keeps the sent callback in memory: after a dev-server restart it answers 404, which is shown as such.
  const retry = async () => {
    setBusy(true); setErr('')
    try {
      const r = await fetch(`/mock-gateway/pay/${payment}/replay`, { method: 'POST' })
      const body = await r.json().catch(() => ({}))
      if (!r.ok) { setDup(null); setErr(body.detail ?? `HTTP ${r.status}`); return }
      setDup(body)
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  if (!payment) {
    return (
      <section className="card receipt">
        <p className="muted">{lang === 'ar' ? 'لا يوجد دفع لعرضه. ادفع مخالفاتك من لوحتك.' : 'No payment to show. Pay your fines from your dashboard.'}</p>
        <button className="btn primary" onClick={() => go('dashboard')}><LayoutDashboard size={16} /> {t.back}</button>
      </section>
    )
  }

  return (
    <>
      <div className="confetti" aria-hidden="true">
        {Array.from({ length: 60 }, (_, i) => (
          <i key={i} style={{ left: `${(i * 37) % 100}%`, background: colours[i % colours.length], animationDelay: `${(i % 12) * 0.08}s` }} />
        ))}
      </div>
      <section className="card receipt">
        <div className="check"><CheckCircle2 size={48} /></div>
        <h2 style={{ margin: '0 0 6px' }}>{t.paid}</h2>
        <div className="grad-text" style={{ fontSize: 38, fontWeight: 800 }}>
          {money(Number(q.get('amount') ?? 0), q.get('currency') ?? 'AED', lang)}
        </div>
        <dl className="kv">
          <dt>{t.receipt}</dt><dd className="mono">{q.get('receipt') || '—'}</dd>
          <dt>{t.gatewayRef}</dt><dd className="mono">{q.get('txn')}</dd>
          <dt>{t.paymentId}</dt><dd className="mono" style={{ fontSize: 12 }}>{payment}</dd>
        </dl>
        <p className="muted" style={{ fontSize: 13 }}>{t.paidNote}</p>
        {dup && (
          <div className="banner info" style={{ textAlign: 'start' }}>
            <ShieldCheck color="var(--cyan)" />
            <div>
              <b className="mono">duplicate: {String(dup.duplicate)} · {dup.receiptNumber}</b>
              <div className="muted" style={{ fontSize: 13 }}>{t.duplicateOk}</div>
            </div>
          </div>
        )}
        {err && <p role="alert" style={{ color: 'var(--red)', fontSize: 13 }}>{lang === 'ar' ? 'تعذّرت إعادة المحاولة: ' : 'Retry not possible: '}{err}</p>}
        <div style={{ display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' }}>
          <button className="btn primary" onClick={() => go('dashboard')}><LayoutDashboard size={16} /> {t.back}</button>
          <button className="btn" onClick={retry} disabled={busy || !payment}><RefreshCcw size={16} /> {t.retryCallback}</button>
        </div>
      </section>
    </>
  )
}
