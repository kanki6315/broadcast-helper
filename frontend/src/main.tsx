import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { registerSW } from 'virtual:pwa-register'
import './lib/authRedirect' // install the global 401 → login interceptor first
import './index.css'
import App from './App.tsx'

// Retiring the service worker (vite.config.ts): registering still matters
// for one more deploy, because it is what makes an installed iPad fetch the
// self-destroying worker that unregisters the old one. Goes in step 2.
registerSW({ immediate: true })

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
