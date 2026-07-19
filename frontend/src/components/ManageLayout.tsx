import { Navigate, NavLink, Outlet } from 'react-router-dom'
import { useIsAdmin } from '../lib/auth'

const MANAGE_LINKS = [
  { to: '/manage/series', label: 'Series settings' },
  { to: '/manage/imports', label: 'Imports' },
  { to: '/manage/logos', label: 'Manufacturer logos' },
  { to: '/manage/users', label: 'Users' },
  { to: '/manage/sessions', label: 'Sessions' },
]

/**
 * Parent boundary for administrative tools — the single route-level admin gate
 * (the backend mirrors it by requiring ROLE_ADMIN on non-GET /api calls).
 */
export default function ManageLayout() {
  const isAdmin = useIsAdmin()
  // Layout renders nothing until /api/me resolves, so by the time this mounts
  // the answer is real — a non-admin deep-linking to /#/manage/* lands on Series.
  if (!isAdmin) return <Navigate to="/" replace />
  return (
    <section className="manage-layout">
      <header className="manage-section-header">
        <h1>Manage</h1>
        <p>Configure series data, imports and shared broadcast assets.</p>
      </header>
      <nav className="manage-subnav" aria-label="Manage">
        {MANAGE_LINKS.map((link) => (
          <NavLink
            key={link.to}
            to={link.to}
            className={({ isActive }) =>
              isActive ? 'manage-subnav-link active' : 'manage-subnav-link'
            }
          >
            {link.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </section>
  )
}
