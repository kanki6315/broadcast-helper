import ChampionshipGrid from './ChampionshipGrid'

/** Championship points by round: each round a column, then Total and Back.
 * Rounds a competitor didn't contest stay blank — a skipped race is not 0. */
export default function StandingsPage() {
  return <ChampionshipGrid mode="points" />
}
