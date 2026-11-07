import { Link, useSearchParams } from 'react-router-dom'
import { useSeason } from './SeasonLayout'
import { hasMultiSeasonRecap } from '../../lib/multiSeasonRecap'
import ChampionshipGrid from './ChampionshipGrid'

export default function HubPage() {
  const { hub } = useSeason()
  const [params] = useSearchParams()
  return (
    <div>
      <div className="page-title-row">
        <h2>Season recap</h2>
        {hasMultiSeasonRecap(hub.seriesName) && <Link to={`/series/${hub.seriesId}/recap${params.get('q') ? `?q=${encodeURIComponent(params.get('q')!)}` : ''}`}>Across seasons</Link>}
      </div>
      <ChampionshipGrid mode="recap" />
    </div>
  )
}
