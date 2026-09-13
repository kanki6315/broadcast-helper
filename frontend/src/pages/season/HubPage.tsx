import ChampionshipGrid from './ChampionshipGrid'

export default function HubPage() {
  return (
    <div>
      <div className="page-title-row">
        <h2>Season recap</h2>
      </div>
      <ChampionshipGrid mode="recap" />
    </div>
  )
}
