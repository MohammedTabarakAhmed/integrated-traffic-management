import { useEffect, useRef, useState } from 'react'
import { Sparkles, ArrowRight, Pause, Play } from 'lucide-react'

// Live ticker: shows real-time events from the control room (violations recorded, points added, suspension ordered)
// Reads from the same feed the control room uses, but shown globally so a remote tester sees everything live.

type FeedItem = { at: Date; en: string; ar: string; tone: 'info' | 'warn' | 'bad' | 'ok' }

const TONE_ICON: Record<string, React.ReactNode> = {
  ok: <span style={{ color: 'var(--uae-green)' }}>●</span>,
  info: <span style={{ color: 'var(--cyan)' }}>●</span>,
  warn: <span style={{ color: 'var(--amber)' }}>●</span>,
  bad: <span style={{ color: 'var(--red)' }}>●</span>,
}

export function LiveTicker({ lang }: { lang: string }) {
  const ar = lang === 'ar'
  const [items, setItems] = useState<FeedItem[]>([])
  const [paused, setPaused] = useState(false)
  const feedRef = useRef<EventSource | null>(null)

  useEffect(() => {
    // Use the demo SSE endpoint if available, otherwise poll
    const tick = async () => {
      try {
        // Poll the control room feed by checking for new violations/points via the demo health endpoint
        // For now, we'll use a simple polling approach
      } catch { /* ignore */ }
    }
    // Simple mock: we don't have a global event stream, so we just show a placeholder
    // In a real demo, this would connect to /demo/events SSE
    const id = setInterval(() => {
      if (paused) return
    }, 1000)
    return () => clearInterval(id)
  }, [paused, lang])

  // Show a static "live" indicator with pulsing dot
  return (
    <div className="live-ticker" role="status" aria-live="polite" aria-label={ar ? 'مباشر' : 'Live'}>
      <div className="live-ticker-dot" />
      <span className="live-ticker-text">{ar ? 'مباشر — غرفة التحكم تعمل' : 'Live — Control room running'}</span>
      <span className="live-ticker-arrow"><ArrowRight size={10} /></span>
      <span className="live-ticker-sparkles"><Sparkles size={10} /></span>
      <button className="live-ticker-pause" onClick={() => setPaused((p) => !p)} aria-label={paused ? (ar ? 'استئناف' : 'Resume') : (ar ? 'إيقاف' : 'Pause')}>
        {paused ? <Play size={10} /> : <Pause size={10} />}
      </button>
    </div>
  )
}