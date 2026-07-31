import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './lib/authRedirect' // install the global 401 → login interceptor first
import { tagBrowserTabApiReads } from './lib/browserTabReads'
import './index.css'
import App from './App.tsx'

// Browser tabs bypass the service worker's stale-first API cache (the
// installed iPad app keeps it) — must wrap fetch before anything renders.
tagBrowserTabApiReads()

// The service worker is registered by <UpdatePrompt> (via useRegisterSW), so the
// "new version available" banner and the periodic update check live together.

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
