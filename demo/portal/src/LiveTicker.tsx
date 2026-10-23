import { Sparkles, ArrowRight, Pause, Play } from 'lucide-react'
import { useState } from 'react'

// Live ticker: shows real-time events from the control room (violations recorded, points added, suspension ordered)
// Reads from the same feed the control room uses, but shown globally so a remote tester sees everything live.

export function LiveTicker({ lang }: { lang: string }) {
  const ar = lang === 'ar'
  const [paused, setPaused] = useState(false)

  // Today the ticker is a live indicator only; when a global event stream (/demo/events) lands, the feed
  // items will be polled here. The pause button already works, so wire the feed straight into the render.
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