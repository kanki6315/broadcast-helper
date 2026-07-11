import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'

const TABS = [
  { to: '/', label: 'Seasons', end: true },
  { to: '/imports', label: 'Imports', end: false },
  { to: '/logos', label: 'Logos', end: false },
  { to: '/series', label: 'Series', end: false },
]

interface Me {
  authEnabled: boolean
  email: string | null
}

export default function Layout() {
  const [me, setMe] = useState<Me | null>(null)
  const authError = new URLSearchParams(window.location.search).get('authError')

  useEffect(() => {
    void fetch('/api/me')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then(setMe)
      .catch(() => setMe({ authEnabled: false, email: null }))
  }, [])

  function login() {
    window.location.href = '/oauth2/authorization/google'
  }

  function logout() {
    void fetch('/logout', { method: 'POST' }).then(() => {
      window.location.href = '/'
    })
  }

  if (!me) return null // brief: waiting on /api/me

  // Auth on but not signed in → a minimal login screen; the button is the only
  // interactive control (no app data loads for a signed-out visitor anyway, since
  // every /api call but /api/me requires a session). A 401 mid-session routes the
  // user back here (see lib/authRedirect).
  if (me.authEnabled && !me.email) {
    return (
      <main className="login-screen">
        <h1>Broadcast Helper</h1>
        {authError ? (
          <p className="error">
            That Google account isn’t on the allowlist. Ask the owner to add your email, then try
            again.
          </p>
        ) : (
          <p className="muted">Sign in to continue.</p>
        )}
        <button type="button" className="login-button" onClick={login}>
          Sign in with Google
        </button>
      </main>
    )
  }

  return (
    <main className="container">
      <div className="topbar">
        <h1>Broadcast Helper</h1>
        {me.email && (
          <div className="account">
            <span className="muted">{me.email}</span>
            <button type="button" onClick={logout}>
              Log out
            </button>
          </div>
        )}
      </div>
      <nav className="tabs">
        {TABS.map((t) => (
          <NavLink
            key={t.to}
            to={t.to}
            end={t.end}
            className={({ isActive }) => (isActive ? 'tab active' : 'tab')}
          >
            {t.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </main>
  )
}
