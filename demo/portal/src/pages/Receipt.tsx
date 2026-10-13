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

  const retry = async () => {
    setBusy(true)
    try {
      const r = await fetch(`/mock-gateway/pay/${payment}/replay`, { method: 'POST' })
      setDup(await r.json())
    } finally {
      setBusy(false)
    }
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
        <div style={{ display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' }}>
          <button className="btn primary" onClick={() => go('dashboard')}><LayoutDashboard size={16} /> {t.back}</button>
          <button className="btn" onClick={retry} disabled={busy || !payment}><RefreshCcw size={16} /> {t.retryCallback}</button>
        </div>
      </section>
    </>
  )
}
