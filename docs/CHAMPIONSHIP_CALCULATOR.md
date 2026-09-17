# Championship calculator

Web: Season → Calculator. iPad: Event → Calculator.

Select an IMSA WeatherTech teams championship and enable one to four class panels.
At least one class stays enabled. All panels share the event and championship
family; each class has independent teams, positions, adjustments, totals and gaps.
Wide layouts place multiple panels in two columns; smaller windows stack them.
Hiding and re-enabling a class retains its scenario for this page visit.
Then add only the teams to compare within each class.
Assign qualifying and race positions in class with the row selectors. Choosing
an occupied position swaps those two teams in that column; omitted positions
contribute zero. The projected table sorts by total and shows rank and gap among
selected teams only. Equal totals retain a shared rank; no official tie-break
claim is made. A signed points adjustment covers penalties or exceptional awards.
Guest eligibility is deliberately not modeled.

Qualifying points are simulated separately, including after qualifying has
happened: the latest official standings normally do not include them until after
the weekend. There is no live timing connection or automatic qualifying import.
Michelin Endurance Cup uses its imported checkpoint labels and separate 5/4/3/2
scoring. All configured checkpoint positions are simulated.

The read-only `GET /api/championships/{id}/calculator` returns imported totals and
per-round points using the existing Recap wire shape. Its event mapping uses
unique venue matches, since cup round numbers differ from season round numbers.
Ambiguous or missing mappings block calculation. The existing recap endpoint and
all actual standings remain unchanged. An event already covered by the imported
ledger (or followed by a scored round) is blocked to avoid double-counting; this
version does not reconstruct historical pre-event standings.

Scenario state lives only in the calculator view. Changing the championship family or
event, or leaving the page, discards all class scenarios. Reset scenario clears
only its own class panel; no scenario is posted to the server.
iPad event/season downloads include eligible calculator baselines for
offline access. Existing downloads need a refresh to acquire this new document.
New native baseline data is adopted explicitly and resets the scenario.

Scoring follows the 2026 IWSC regulations, articles 12.20 and 40.6 and Attachment
6: race 350/320/300/280/260, then 250 down to 10 (P30 onward); qualifying is 10%
of that. The source is linked from both calculators. Rules for other series,
driver championships, eligibility, shortened races and tie-breaks are outside
this initial model.

Verification:

- `cd frontend && npm run test:calculator`
- `cd frontend && npm run test:calculator:browser` (Playwright; set
  `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` for a local Chrome executable)
- Backend: `./gradlew test --tests com.pitpass.browse.ChampionshipCalculatorTest --tests com.pitpass.browse.RecapCarNumberAliasTest`
- iPad: `ChampionshipCalculatorTests` in the PitPass Xcode scheme.

Tests cover scoring boundaries, independent qualifying, penalties, sparse
selection, swaps, ties, baseline guards, unchanged recap data and read-only
browser requests. Native layout fixtures use synthetic teams and points.
