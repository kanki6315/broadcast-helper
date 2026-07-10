import { useState } from 'react'
import './App.css'
import SeriesPage from './pages/SeriesPage'
import ImportsPage from './pages/ImportsPage'
import EventsPage from './pages/EventsPage'
import StandingsPage from './pages/StandingsPage'
import ImagesPage from './pages/ImagesPage'
import LogosPage from './pages/LogosPage'
import SheetPage from './pages/SheetPage'

const TABS = ['Imports', 'Events', 'Standings', 'Images', 'Logos', 'Series'] as const
type Tab = (typeof TABS)[number]

function App() {
  const [tab, setTab] = useState<Tab>('Imports')

  // Sheets open standalone (no app chrome) so the printed page is clean.
  const sheetMatch = window.location.hash.match(/^#\/sheet\/(\d+)$/)
  if (sheetMatch) {
    return <SheetPage eventId={Number(sheetMatch[1])} />
  }

  return (
    <main className="container">
      <h1>Broadcast Helper</h1>
      <nav className="tabs">
        {TABS.map((t) => (
          <button key={t} className={t === tab ? 'tab active' : 'tab'} onClick={() => setTab(t)}>
            {t}
          </button>
        ))}
      </nav>

      {tab === 'Imports' && <ImportsPage />}
      {tab === 'Events' && <EventsPage />}
      {tab === 'Standings' && <StandingsPage />}
      {tab === 'Images' && <ImagesPage />}
      {tab === 'Logos' && <LogosPage />}
      {tab === 'Series' && <SeriesPage />}
    </main>
  )
}

export default App
