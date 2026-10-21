import { StrictMode, Component, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App'
import { I18nProvider } from './i18n'

// Apply the saved theme before the first paint.
try { document.documentElement.dataset.theme = localStorage.getItem('itms.theme') || 'dark' } catch { /* storage unavailable */ }

// Root-level crash guard: a render error shows here instead of a blank white page.
class RootGuard extends Component<{ children: ReactNode }, { err: Error | null }> {
  state = { err: null as Error | null }
  static getDerivedStateFromError(err: Error) { return { err } }
  componentDidCatch(err: Error) { console.error('[ITMS] render crash:', err) }
  render() {
    if (this.state.err) {
      return (
        <div style={{ padding: 40, fontFamily: 'monospace', color: '#f43f5e', background: '#070b16', minHeight: '100vh' }}>
          <h2 style={{ color: '#e8edf9' }}>The page crashed. Show this to the developer:</h2>
          <pre style={{ whiteSpace: 'pre-wrap', color: '#fbbf24' }}>{String(this.state.err.stack || this.state.err.message)}</pre>
          <button onClick={() => { this.setState({ err: null }); location.hash = '#/home' }}
            style={{ padding: '10px 18px', borderRadius: 10, border: '1px solid #f43f5e', background: 'none', color: '#e8edf9', cursor: 'pointer' }}>
            Reload app
          </button>
        </div>
      )
    }
    return this.props.children
  }
}

// Also surface async errors that would otherwise vanish.
addEventListener('unhandledrejection', (e) => console.error('[ITMS] unhandled rejection:', e.reason))

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RootGuard>
      <I18nProvider>
        <App />
      </I18nProvider>
    </RootGuard>
  </StrictMode>,
)
