# Championship calculator

Web: Season → Calculator. iPad: Event → Calculator.

Select an IMSA WeatherTech or Michelin Pilot Challenge teams championship and
enable one to four available class panels. Pilot Challenge supports GS and TCR.
At least one class stays enabled. All panels share the event and championship
family; each class has independent teams, positions, adjustments, totals and gaps.
Wide layouts place multiple panels in two columns; smaller windows stack them.
Hiding and re-enabling a class retains its scenario for this page visit.
Then add only the teams to compare within each class.
Assign positions in class with the row controls: qualifying and race for
WeatherTech, race only for Pilot Challenge. Choosing
an occupied position swaps those two teams in that column; omitted positions
contribute zero. The projected table sorts by total and shows rank and gap among
selected teams only. Equal totals retain a shared rank; no official tie-break
claim is made. A signed points adjustment covers penalties or exceptional awards.
Guest eligibility is deliberately not modeled.

Web position controls remain select menus. On iPad, touch a position to reveal
a preview slider, then move the same finger left or right without lifting.
Release to apply the changed position once; points, swaps and row sorting wait
until release so the selected row stays still during the drag. Slide left to
the blank position to clear it. Holding at either screen edge continues stepping
within the allowed range. A cancelled gesture discards its preview, and a touch
without a position change makes no assignment. Scroll outside the position
controls to move the table. VoiceOver supports increment/decrement adjustment
and a “Clear position” custom action.

WeatherTech qualifying points are simulated separately, including after qualifying has
happened: the latest official standings normally do not include them until after
the weekend. There is no live timing connection or automatic qualifying import.
Michelin Endurance Cup uses its imported checkpoint labels and separate 5/4/3/2
scoring. All configured checkpoint positions are simulated. Pilot Challenge has
no qualifying points or qualifying selector; its projection adds race points
and the signed adjustment to the imported total.

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
offline access. The download planner uses the same supported-championship
predicate as the calculator, so Pilot Challenge baselines are included
automatically. Existing downloads need a refresh to acquire these documents.
New native baseline data is adopted explicitly and resets the scenario.

WeatherTech scoring follows the 2026 IWSC regulations, articles 12.20 and 40.6 and Attachment
6: race 350/320/300/280/260, then 250 down to 10 (P30 onward); qualifying is 10%
of that. Pilot Challenge uses the same race-points scale without qualifying
points. Both calculators link to the relevant 2026 regulations:
[IWSC](https://www.imsa.com/wp-content/uploads/sites/32/2026/05/20/2026-IMSA-SPORTING-REGULATIONS-and-SSR-IWSC-Blackline-031126.pdf)
or [IMPC](https://www.imsa.com/wp-content/uploads/sites/32/2026/05/20/2026-IMSA-SPORTING-REGULATIONS-and-SSR-IMPC-Blackline-031126.pdf).
Rules for other series,
driver championships, eligibility, shortened races and tie-breaks are outside
this initial model.

Verification:

- `cd frontend && npm run test:calculator`
- `cd frontend && npm run test:calculator:browser` (Playwright; set
  `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` for a local Chrome executable)
- Backend: `./gradlew test --tests com.pitpass.browse.ChampionshipCalculatorTest --tests com.pitpass.browse.RecapCarNumberAliasTest`
- iPad: `ChampionshipCalculatorTests` and `PositionScrubberTests` in the PitPass
  Xcode scheme.

Tests cover scoring boundaries, independent qualifying, penalties, sparse
selection, swaps, ties, baseline guards, unchanged recap data and read-only
browser requests. Position-control tests cover preview before release, a single
commit, clearing, bounds, cancellation and accessibility increments/decrements,
with active-slider layout captures. Native layout fixtures use synthetic teams
and points.
