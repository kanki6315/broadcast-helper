import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { registerSW } from 'virtual:pwa-register'
import './lib/authRedirect' // install the global 401 → login interceptor first
import './index.css'
import App from './App.tsx'

// Install the service worker (offline app shell + read-only /api cache). The
// plugin is registerType:'autoUpdate', so a new deploy silently swaps in on the
// next launch. No-op in dev (SW isn't emitted there).
registerSW({ immediate: true })

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
