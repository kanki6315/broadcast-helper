import { NavLink, Outlet } from 'react-router-dom'

const MANAGE_LINKS = [
  { to: '/manage/series', label: 'Series settings' },
  { to: '/manage/imports', label: 'Imports' },
  { to: '/manage/logos', label: 'Manufacturer logos' },
]

/**
 * Parent boundary for administrative tools. When roles are introduced, gate
 * this route and its matching backend endpoints rather than scattering checks
 * across the individual pages.
 */
export default function ManageLayout() {
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
