import { HashRouter, Routes, Route, useParams } from 'react-router-dom'
import './App.css'
import Layout from './components/Layout'
import SeasonsLandingPage from './pages/SeasonsLandingPage'
import SeasonHubPage from './pages/SeasonHubPage'
import EventDetailPage from './pages/EventDetailPage'
import StandingsDetailPage from './pages/StandingsDetailPage'
import ImportsPage from './pages/ImportsPage'
import LogosPage from './pages/LogosPage'
import SeriesPage from './pages/SeriesPage'
import SheetPage from './pages/SheetPage'

// The sheet renders standalone (no app chrome) so the printed page is clean.
function SheetRoute() {
  const { eventId } = useParams()
  return <SheetPage eventId={Number(eventId)} />
}

export default function App() {
  return (
    <HashRouter>
      <Routes>
        <Route path="/sheet/:eventId" element={<SheetRoute />} />
        <Route element={<Layout />}>
          <Route path="/" element={<SeasonsLandingPage />} />
          <Route path="/seasons/:seasonId" element={<SeasonHubPage />} />
          <Route path="/events/:eventId" element={<EventDetailPage />} />
          <Route path="/championships/:championshipId" element={<StandingsDetailPage />} />
          <Route path="/imports" element={<ImportsPage />} />
          <Route path="/logos" element={<LogosPage />} />
          <Route path="/series" element={<SeriesPage />} />
        </Route>
      </Routes>
    </HashRouter>
  )
}
