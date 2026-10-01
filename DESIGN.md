---
name: Pit Pass
description: A skim-first motorsport reference — Apple-clean restraint fused with on-air timing-terminal density.
colors:
  # Hex values are sRGB approximations for tooling; the canonical OKLCH tokens
  # live in frontend/src/index.css and .impeccable/design.json (colorMeta).
  # "-dark" keys are the dark-theme values of the same role.
  bg: "#ffffff"
  bg-dark: "#16171d"
  surface: "#f3f4f7"
  surface-dark: "#1e2027"
  surface-2: "#e9ebef"
  surface-2-dark: "#262933"
  border: "#dfe0e5"
  border-dark: "#2e303a"
  border-strong: "#cccfd6"
  border-strong-dark: "#3b3e4a"
  ink: "#0b0812"
  ink-dark: "#f3f4f6"
  text: "#3d3a45"
  text-dark: "#b6bcc7"
  text-muted: "#5e626e"
  text-muted-dark: "#878e9e"
  broadcast-amber: "#b8791f"
  broadcast-amber-dark: "#f0b84a"
  amber-ink: "#8f5e12"
  on-accent: "#221806"
  on-accent-dark: "#2a2008"
  error: "#b32318"
  error-dark: "#f5776b"
  success: "#067647"
  success-dark: "#47cd89"
  info: "#1758d3"
  info-dark: "#7fa8f2"
  res-win: "#bfeccd"
  res-win-dark: "#124631"
  res-top3: "#f7dce9"
  res-top3-dark: "#4b2337"
  res-top5: "#e2dcf8"
  res-top5-dark: "#362a58"
  res-dnf-bg: "#25262c"
  res-dnf-bg-dark: "#a2a4ae"
  res-dnf-ink: "#e6e8eb"
  res-dnf-ink-dark: "#101117"
typography:
  headline:
    fontFamily: "Inter Variable, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Inter Variable, system-ui, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 600
    letterSpacing: "-0.02em"
  body:
    fontFamily: "Inter Variable, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Inter Variable, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
  caption:
    fontFamily: "Inter Variable, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 600
  data:
    fontFamily: "JetBrains Mono Variable, ui-monospace, monospace"
    fontSize: "0.875rem"
    fontWeight: 400
    fontVariation: "tabular-nums"
rounded:
  xs: "3px"
  sm: "4px"
  md: "6px"
  lg: "10px"
  pill: "999px"
spacing:
  "1": "4px"
  "2": "8px"
  "3": "12px"
  "4": "16px"
  "5": "24px"
  "6": "32px"
  "7": "48px"
  "8": "64px"
components:
  button-primary:
    backgroundColor: "{colors.broadcast-amber}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.md}"
    padding: "6px 14px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.md}"
    padding: "6px 14px"
  seg-btn-active:
    backgroundColor: "{colors.bg}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "4px 12px"
  class-chip:
    backgroundColor: "{colors.bg}"
    textColor: "{colors.text}"
    rounded: "{rounded.pill}"
    padding: "3px 10px"
  class-tag:
    textColor: "#ffffff"
    rounded: "{rounded.sm}"
    padding: "0 6px"
  input:
    backgroundColor: "{colors.bg}"
    textColor: "{colors.text}"
    rounded: "{rounded.md}"
    padding: "6px 10px"
---

# Design System: Pit Pass

## 1. Overview

**Creative North Star: "The Timing Tower"**

The trackside timing tower shows the running order at a glance — authoritative,
unadorned, instantly legible from a distance and under pressure. Broadcast
Helper is an instrument for reading facts fast: during desk prep, live on air,
and trackside in a dim booth. The system fuses **Apple-clean restraint**
(immaculate spacing, one confident accent, nothing decorative) with **on-air
timing-terminal density** (tight rows, tabular numbers, meaningful class
colors). The material logic is cockpit night lighting: cool graphite panels,
one warm amber instrument light.

Dark mode is a first-class tuned surface, not a variant — theme is forced via
`data-theme` on `<html>` ('light' | 'dark'; absent follows the system), applied
before first paint. Motion is **booth-fast**: 70ms state feedback and 140ms
transitions with an ease-out-quart curve, never choreography — the user asked
for sub-100ms because a 150ms tab switch feels slow mid-broadcast. Every
animation collapses under `prefers-reduced-motion`.

This system rejects: the generic SaaS dashboard (gradients, hero-metric cards),
consumer/gamified looks, enterprise gray-on-gray clutter, and decorative
flourish of any kind. If a change makes a table harder to skim, it is wrong
regardless of how it looks.

**Key Characteristics:**
- Skim-first: tabular numerals, aligned columns, strong scanning hierarchy.
- Restrained chrome; broadcast amber is the only voiced accent.
- Class colors and result tints are data, never decoration.
- Dark-mode-first, WCAG AA floor on every pair.
- Booth-fast motion (70–140ms), state feedback only.

## 2. Colors

Cool graphite neutrals (hue 277, chroma ≤0.014) carrying a single warm amber
accent, a semantic state trio, and two functional data palettes (per-series
class colors and result tints). Canonical values are OKLCH in
`frontend/src/index.css`; the hex values here and in the frontmatter are sRGB
approximations.

### Primary
- **Broadcast Amber** (light `#b8791f` / oklch(60% 0.115 75); dark `#f0b84a` /
  oklch(80% 0.13 80)): the one instrument light. Primary actions, the active
  tab underline, focus rings, selection washes (`--accent-tint` at 10–12%
  alpha), and the recap's pole marker. As **text** on light backgrounds it
  darkens to **Amber Ink** (`#8f5e12`, ≥4.5:1 on white); on dark, the amber is
  bright enough to be its own text. Fills carry near-black ink (`--on-accent`).

### Neutral
- **Graphite ground** (light `#ffffff`; dark `#16171d`): the content surface.
- **Panel** (`--surface`, light `#f3f4f7`; dark `#1e2027`) and **Raised**
  (`--surface-2`): toolbars, widgets, segmented controls, sticky table headers.
  Depth in dark mode comes from these lightness steps, not shadows.
- **Hairline / Strong borders** (`--border`, `--border-strong`): the quiet grid
  every table sits on.
- **Ink / Body / Muted text** (light `#0b0812` / `#3d3a45` / `#5e626e`; dark
  `#f3f4f6` / `#b6bcc7` / `#878e9e`): all three clear AA on their surfaces.

### Semantic States
- **Error** (light `#b32318`; dark `#f5776b`) with a 9–13% `--error-tint` wash
  for panels; **Success** (`#067647` / `#47cd89`); **Info** (`#1758d3` /
  `#7fa8f2`). Warning has no separate hue — amber is already spoken for.

### Functional — Result Tints (recap cells and stat counts)
- **Win** (green tint), **Top 3** (pink tint), **Top 5** (violet tint): fills
  behind start→finish numbers, tuned per theme so default ink stays AA on top.
  The same three tints back non-zero win / podium / top-5 **counts** on the
  Stats table, so one vocabulary answers "how did that go?" whether the number
  is a finishing position or a tally of them. `lib/raceForm.positionTier` owns
  the 1 / ≤3 / ≤5 thresholds — every surface delegates to it rather than
  restating the numbers.
- **DNF** (**inverts against the surface in both themes**: dark chip `#25262c`
  on light, light chip `#a2a4ae` on dark — measured 15.1:1 and 7.3:1 against
  their row): a retirement is the most story-changing fact in a recap, so it
  reads loud at the desk and in the booth alike. It always carries the **`R`
  text mark** as well — see the Color-Is-Data Rule.

### Functional — Class Palette
- Motorsport class colors come from per-series `class_style` rows in the
  database (`--class-color` / `--chip-color` custom properties), never from
  this spec. They are data.
- Because the values are user-configured they can land anywhere in the space —
  IMSA's GTP is `#000000`, which measured **1.16:1** as an active border and
  **1.28:1** as a filled pill against the dark ground. Two rules below exist
  specifically to keep a class colour from having to do a job it can't.

### Named Rules
**The One Instrument Rule.** Broadcast amber is the *only* voiced accent, on
≤10% of any screen. Its scarcity is what makes it read as an instrument light.

**The Color-Is-Data Rule.** Any color that carries meaning (class, result tier,
status) is always paired with a label, code, or number. Hue never encodes
information alone. A win/podium is inferable from the finishing number itself;
a **retirement is not**, so the DNF cell carries a literal `R` mark (plus
screen-reader text) and never leans on its fill.

**The Solid-Fill Rule.** No gradients on the accent, ever. A gradient amber
button is the SaaS trap; a solid one is a broadcast instrument.

**The Selection-Is-Amber Rule.** Amber marks **selection**; class colour marks
**identity**. Never swapped. A class colour cannot carry selection — it is
user-configured and free to be near-black, at which point the "on" state renders
*dimmer than "off"* (measured: active GTP chip 1.16:1 vs inactive 1.8:1 in dark).
Amber is the one accent guaranteed legible in both themes, so the active chip
takes an amber border + ring and lets the swatch keep saying which class.

**The Hairline Rule.** Every block filled with a class colour — chip swatch,
class tag, class band — carries a `--border-strong` hairline. Without it a
near-black class dissolves into the dark ground and its label floats in space.

## 3. Typography

**UI Font:** Inter Variable (system-ui fallback), self-hosted via @fontsource.
**Data Font:** JetBrains Mono Variable (ui-monospace fallback) — every number
that lives in a column: positions, points, times, car numbers, years.

**Character:** one restrained sans does the talking; identity comes from
spacing, alignment, and the amber accent, not letterforms. Numerals lock into
columns so the eye scans straight down a results table.

### Hierarchy
Fixed rem scale, ~1.2 ratio (product UI — predictable, never fluid):
- **Headline** (600, 1.5rem, `letter-spacing: -0.02em`): the page title
  (series + season). One per view.
- **Title** (600, 1.25rem): section headings, session names.
- **Body** (400, 1rem, 1.5): prose and labels; prose capped at 65–75ch.
- **Label** (500–600, 0.875rem): tabs, buttons, segmented controls, chips.
- **Caption** (600, 0.75rem, `--text-muted`): table headers, round numbers,
  legends. Sentence case — never tracked uppercase.
- **Data** (mono, 0.875rem, `font-variant-numeric: tabular-nums`): numeric
  cells, right-aligned; car numbers at 700.

### Named Rules
**The Tabular Rule.** Every number in a column uses tabular figures and aligns
— positions, times, points, car numbers must scan as a straight vertical read.

**The No-Eyebrow Rule.** No tiny uppercase wide-tracked kickers. Hierarchy
comes from size, weight, and space.

## 4. Elevation

Flat by default — a timing screen has no drop shadows. Depth is conveyed by the
hairline border grid and the surface lightness steps (`--bg` → `--surface` →
`--surface-2`), which is also how dark mode expresses elevation. Shadows exist
only as state responses.

### Shadow Vocabulary
- **Raise** (`--shadow-raise`: `0 1px 2px` + `0 2px 8px`, ~8%/6% black in
  light, 30%/25% in dark): active segment buttons and theme-toggle thumbs —
  the "pressed instrument key" cue.
- **Modal** (`--shadow-modal`: `0 4px 12px` + `0 12px 40px+`): overlays only
  (team-sheets PDF modal, driver/team info modals, the starting-grid modal, the
  import modals).
- **Focus ring** (`--focus-ring`: 2px bg gap + 2px amber): every
  `:focus-visible`, no exceptions.

### Named Rules
**The Flat-Instrument Rule.** Surfaces are flat at rest. If a shadow appears
with no state change behind it (hover, focus, overlay), it is decoration —
remove it.

## 5. Components

Shared vocabulary in `frontend/src/App.css`; season surfaces in
`frontend/src/pages/season.css`. Every interactive component defines default,
hover, focus-visible, active, and disabled states; transitions run at
`--t-fast` (70ms) ease-out-quart.

### Data Grid (the signature component — `.grid-table` in `.grid-frame`)
The heart of the tool: recap, standings, lineups, results. Hairline row
dividers, `border-collapse: separate`, sticky headers (surface background) and
sticky identity columns with explicit left offsets (disabled below 700px).
**The grid lives in normal document flow — no nested scrollbox.** Its bordered
`--radius-md` frame (`.grid-frame`, `width: max-content` with `min-width:
100%`) hugs the table; the page itself scrolls, vertically always and
horizontally only when a season outgrows the viewport. Headers pin to the
viewport top, ident columns to the viewport left. Because the frame cannot
clip its table (clipping would kill viewport-sticky headers), the corner cells
carry their own `border-radius` and the last row yields its divider to the
frame border. Round columns show a mono venue code over a muted "Rd n". Class sections divide on a
**class band** — a full-width row filled with the class color, name always
printed on it. Result cells stack one `.race-line` per race, tinted by finish
tier, with the amber **P** for pole and DNS/skips as quiet muted marks. Where
a round ran more than one race each line carries a muted `.race-tag` naming
which one (`H1`, `C`, `F`) — the same notation as the standings breakdown, via
the shared `sessionTagList`. The tag is derived across the **round's whole
race list**, not the races that competitor contested, so a driver who ran only
the fourth heat reads `H4` rather than `H`. It sits outside the tinted chip so
the result tints stay the width of the numbers they back. A row keyed by team
name rather than car number (Mustang's DH Entrants) gathers every car the team
ran, so where a round holds more than one car the cell reads one `.race-car-row`
per car instead — the muted car number leading, that car's tagged chips
following in running order — rather than an unlabelled stack of duplicates.

The **standings** grid prints how each round paid, not one summed number: a
`.pts-cell` stacks one `.pts-line` per scoring session the competitor ran,
race points right-aligned in the digit column and every extra spelled out in a
shared marks gutter — amber `+1P` pole, ink `+1F` fastest lap, a bare muted
`+10` for a PDF's lumped bonus (no letter code: the source doesn't say pole or
fastest lap), error-red `−n` penalty — so the arithmetic sits on the table,
not in a tooltip (the line's title still gives the session total; the cell's
title the round total). Zero stays printed but recedes; a skipped round keeps
the single `—`.

**A session the competitor sat out contributes no line at all.** Six blank
markers for a driver who ran one heat of a six-race weekend cost a column of
vertical space to say nothing, so the cell shows only what happened. That
makes the muted tag load-bearing — it is the only thing identifying which
session a number belongs to — so tags are **abbreviated from the real session
names**, not positions: Qualifying → `Q`, Heat → `H`, Feature → `F`,
Race/Round → `R`, numbered only where that word repeats inside the round.
PESC's Sachsenring reads `Q · H1 H2 H3 H4 · F`, and a Feature is never
flattened into "R5". The number is positional within the round, never the
source's own ordinal, so Carrera Cup Asia's "Round 3"/"Round 4" tag `R1`/`R2`
under a column headed Rd 2 instead of contradicting the header.

Single-session rounds print the bare number with no tag, so a source that
scores one session per round looks unchanged. **Both** legends are built from
the data on screen: each names the session words actually present (`H = heat`,
`C = consolation`), the points one keeps the `+`/`−` sign on every mark —
which is what separates a Feature's `F` tag from a `+1F` fastest lap — and the
recap one prints the retirement mark as the superscript it actually is, so it
never reads as an `R` race tag.

A **Breakdown ⇄ Round total** `.seg` toggle (URL `?pts=total`, like every
other selection on the page) collapses the cells back to one number per round
for a high-level championship read. It appears only where the two views
actually differ; with nothing to break down, a toggle that changes nothing is
noise.

### Stats Table (`.stats-table`, a Data Grid variant)
Per-driver tallies split by race format: a **two-row header** (format group
name over its St / W / P3 / T5 / DNF sub-columns), a trailing Qualifying group
(Pole / T5) only where quali data exists, and a hairline `.grp-start` opening
each group so five-wide runs of digits stay scannable. Non-zero win, podium
and top-5 counts wear the recap's result tints as `.stat-chip`s; zeros recede
to `--text-muted` at reduced opacity, and a format a driver never contested
prints "·", not 0 — never entered and finished-nowhere are different facts.
The W / P3 / T5 cells pair the count with its **share of starts** (`.stat-pair`,
a two-column inline grid: the chip right-aligned, then a caption-sized muted
`33%` in a reserved `4ch` slot). The slot is kept even when empty so every
count in a column sits on one axis; a zero shows no rate because 0% beside 0
says nothing twice. The rate is derived in the browser from the counts already
fetched — never a second request — and sorting stays on the count.
This grid runs **denser than the standard Data Grid** (tighter cell padding,
narrower chips, centered values) because up to six column groups have to fit
one screen; the ident columns keep normal padding so names don't crowd. Class
sections use the same class band as the recap.

**Every column sorts, and the affordance costs no space.** The header label
*is* the button (`.sh-sort`, a bare control inheriting the cell's type) and it
spans the whole cell via negative margins cancelled by equal padding — so the
click target is the full cell (≥24×24, WCAG 2.5.8) while row 1 stays 30px, row
2 stays 27px, and no column changes width. The direction caret is a 5×4px
triangle absolutely positioned off the label's right edge, living in slack the
header already had (the tightest sub-head, "DNF", carries ≥9px either side of
its centred label). Nothing about the active state may re-measure text —
weight stays put and only **colour** changes, because 27 headers reflowing on
click would shift the grid under the reader.

At rest a column says nothing; hover and `:focus-visible` preview the caret at
45% to show what the next click gives; the sorted column prints it solid in
`--accent-ink` with its label in `--ink`. Clicking cycles
**default → opposite → off**: the third click restores the backend's composite
ranking (wins, then podiums), which no single column reproduces, so it has to
stay reachable. Tallies open descending ("who has the most"), text columns
ascending. **Sorting reorders within each class section, never across** — the
class band is a structural division, not a row property — and a format the
driver never contested (`·`) sinks to the bottom in *both* directions, because
"didn't enter" is not a score of zero. `aria-sort` rides on the `th`, and each
button names its group and meaning ("Sprint — Podiums (top 3)"), which is how
a screen reader recovers what the two-row header says spatially.

### Event Sheet (`frontend/src/pages/sheet.css`)
The standalone per-event reference (`/sheet/:eventId`), on the same token
layer and result vocabulary as the recap: class bands with computed ink, one
`tbody` per entry (main row + season form strip), zebra as the class colour
mixed 8% into `--bg`, `.race-line` chips for start/finish. The Start column
shows only the grid slot. Where a grid file names a starting driver, their
existing Drivers-list name keeps its normal color and weight with a thin underline and an
`Underlined = starting driver` legend; the iPad sheet and PDF use the same treatment. Rows deep-link to
the team-sheets modal (the car number is a real button for keyboard reach);
prior-year cells are contentEditable and save on blur. Its `@media print`
block forces the light token values on the `.sheet` scope, so Print/Save-PDF
emits the compact US-Letter deliverable from either theme. Manufacturer
wordmark logos sit on a small white chip in dark mode only.

### Live Timing Page (`frontend/src/pages/TimingPage.tsx`, `timing.css`)
The timing tower itself (`/timing/:eventId`, or `/timing/weekend/:id` for one
series weekend as the feed has it, filed under an event or not), chrome-less
like the sheet because it lives on a second screen in the booth. Two views on a `.seg`
tablist (URL `?view=drive`): **Tower** and **Drive time**. Viewers only
read. Admins also get the shared switch at the end of the top bar, with the
iPad's wording and states: **Connect for this event**, **Score this event**
when the feed is following another event, and **Disconnect**. Disconnecting
stops timing for every user, so it asks first, **inline** (the question plus
"Keep connected" and a `.btn-danger` "Disconnect for everyone") rather than
in a modal.

- **Timing home** (`/timing`, `TimingHomePage.tsx`, "Timing" in the header):
  the same chrome-less frame. What is on track and where it is filed, the
  admin's plain **Connect** (no event: every series is filed by
  championship), then recorded weekends — a heading per track and dates, a
  `.grid-table` row per series with a "Filed under" select for admins
  (Automatic / Not in Pit Pass / an event) and its sessions as plain links in
  schedule order. A session filed nowhere says so once above the tower, not
  as "not entered" on every row.

- **Tower**: one `.grid-table` with a class band per class (the series'
  `class_style` colour, computed ink, name always printed). It runs tighter
  than the standard grid (3px × 8px cells), the way the Stats table earns its
  density, because a tower is one line per car. Columns: position, car
  number (a button opening the car panel), current driver with a muted
  rating letter, team (shortened to 16ch below 1200px, dropped below
  900px), laps, gap, interval, last, best, and stint (laps plus a time
  counting up from the stint's start). **Fastest lap in class** sits on the
  violet `--res-top5` tint in bold ink. Timing screens already read purple as
  "fastest", and the cell's title and screen-reader text say it. The last
  lap borrows the same convention: violet when it set the class's fastest
  lap, the green `--res-win` tint in weight when it was the car's own best,
  plain otherwise. A **Pits** column counts stops as Al Kamel's tower does
  (and the Pits view), with the last stop's pit-lane time muted beside it.
  With participant details on, **S1–S3** follow Best and fill in as each
  sector is run, in the same purple and green; a time left from the
  previous lap sits in muted ink, an invalid one is struck through in error
  red. The class band carries the class's best sectors and their holders
  plus the ideal lap. An out lap wears a quiet outlined `Out` mark. With
  sectors, the team column drops below 1100px instead of 900px, so the
  tower still fits 1024px beside Energy. In a race, places gained or lost
  in class since the start follow the position as ▲2 in success green or
  ▼1 in error red (shape and number carry it; the words are for screen
  readers), in a reserved slot so positions stay in one column. A row that
  changes place flashes an amber wash that fades over 3s (a steady wash for
  4s under reduced motion); the first tower seen never flashes. Under the
  clock, the field at a glance: "24 on track · 21 in pit · 0 stopped · 0
  retired" (stopped only with participant details). A **Columns**
  disclosure above the tower lets each viewer hide Sectors, Pits or Energy
  and turn on **Top** (best speed trap, class-fastest on the violet tint),
  which is off by default so the tower still fits 1024px; only columns the
  feed has data for are offered, and the choice is remembered in that
  browser only. A car in the pit carries an amber-tint
  `Pit` mark; a retired car stays in its place in muted ink with its status
  in words. Interval and last lap drop below 640px. An **Energy** column
  (IMSA telemetry) appears only once some car has a reading: the percentage
  and a muted "~9 L" projection of laps left at the stint's average use.
  With it the tower runs 6px cell padding and 12ch team names below 1200px,
  so it still fits 1024px. The stint clock counts on
  the feed's own clock (`feedClockMs`), so a replay or a finished session
  stops instead of counting against today.
- **Car panel** (`LiveCarModal`, `.lc`): a native `<dialog>` like the
  starting-grid modal, the only thing on the page that scrolls inside
  itself. Laps are **newest first** (live, the lap that matters is the one
  just done), with sectors, a dotted amber underline for a sector run under a
  flag (flag name on hover), pit in/out and track limits as words, and an
  invalid lap struck through in error red. Stints list driver, type, lap
  range, start, length and the driver's track-time total. Drivers are named
  by surname, never the feed's three-letter code.
- **Drive time**: class bands, then crews as units (one divider per car).
  Each driver has their time, a 120px meter (share of the maximum; an ink
  tick at the minimum) and a status **in words**: "Over by 5:00" in error
  red, "30:00 to go", "OK · 1:00:00 left" in success green, or a muted "No
  rule". An "In car" tag marks the current driver. The meter is shape only;
  the words are the fact. Admins edit rules inline in a `--surface` panel.
  Times are parsed as hours, h:mm or h:mm:ss, and an unreadable one is
  reported before anything is sent.
- **Race control** (`RaceControl.tsx`, `.rc-*`): above the tower, a
  `--surface` strip labelled "Race control" in muted caps, with what race
  control's screen shows now in ink, semibold, and — when it is not already
  on the screen — the newest message in body ink after its track time in
  mono. "All messages" opens the **Race control** view: the session's log,
  newest first, as a `.grid-table` of time (at the track while live, else
  the viewer's clock, said under the table), class and message. Race
  control's own colours appear only as a 3px bar beside each message, never
  as a fill, so text contrast is ours in both themes; a blinking message
  blinks only its bar, and not at all under reduced motion. Under 640px the
  label and link share the top line and the messages run below.
- **Session clock**: the page's one big number, right of the title (below
  it under 640px), in tabular figures: time to go, counted down in the
  browser from the feed's status, or "Lap 12 of 30" for a lap-limited race.
  A red flag freezes it in error red with "Clock stopped" in words. The time
  of day at the track sits beneath it in muted xs, while the feed is live.
- Feed state is always words ("Live", "Reconnecting · last known", "Off").
  The dot beside it only repeats them. When the feed is off or following
  another event, the tower is replaced by an empty state that says which and
  links there.

### Buttons
- **Shape:** `--radius-md` (6px), 6px 14px padding, label type.
- **Primary:** solid amber, `--on-accent` ink; hover mixes 12% ink into the
  fill. **Secondary/default:** `--surface` with `--border-strong`, hover
  `--surface-2`. **Disabled:** 50% opacity, `not-allowed` cursor.

### Segmented Control (`.seg` / `.seg-btn`)
The filter vocabulary (championship/cup, teams/drivers, season/all-time,
sub-nav): a `--surface` pill-box (radius 6px, 2px padding); the active segment
lifts on `--bg` with `--shadow-raise` and ink text. Overflows scroll invisibly
within the pill — never the page. Used two ways: as a **one-of** switch, and on
the Stats page as a **many-of** visibility control where each segment toggles a
column group independently (the last visible group can't be turned off — a
table showing nothing answers nothing).

### Chips
- **Class filter chip** (`.class-chip`): pill outline + 10px color swatch +
  class code; active fills 12% of the class color and borders in it.
- **Class tag** (`.class-tag`): solid class-color block, white 700 text —
  the inline class marker in tables and widgets.
- **Round chip** (`.round-chip`): venue code over "Rd n"; active = amber
  border + `--accent-tint` fill.
- **Car filter chip** (`.rc-car-chip`, race-control log): mono tabular number,
  outline at rest; active = amber border + `--accent-tint`. Same active
  vocabulary as the round chip, one tier smaller.

### Results Page (`frontend/src/pages/season/ResultsPage.tsx`)
The event's results, one session at a time. Round chips pick the event; a
**session tablist** (the `.seg` control, roving arrow keys) switches
qualifying ⇄ race and hides when there's only one session. The classification
is a Data Grid whose column set follows the session and is computed from the
whole session, never the class-filtered rows — flipping a class chip never
reshapes the table. Race classifications add **± Pos** beside the finish to
show positions gained or lost from the published starting grid, and **Pit
stops** beside laps only when every result row carries the provider's count —
missing data is never presented as a zero-stop race. On a qualifying session
the driver column names the one driver the session credits, and its header says
which claim that is:
**"Qualified by"** where the grid file named a qualifying driver of record,
else **"Fastest lap by"** (the timing provider's seat), else plain "Drivers".
Where a header promises attribution but a row has none, the cell still prints
the full crew and says so on hover — "one of these two" is honest, a dash
isn't. Supporting surfaces:
- **Starting-grid modal** (`StartingGridModal`, `.sg`): the grid as a grid —
  pole front-left, cars staggered odd-left / even-right behind a "Start line",
  with the team, the **starting driver**, and the qualifying time under each.
  The qualifier appears as a second `Q:` line only when it differs from the
  starter, so the common case stays one clean line; both lines are omitted
  entirely for sources that name no driver. Native `<dialog>` on the token
  layer; a class filter *lifts* its cars onto `--bg` rather than removing slots
  (a grid is a fact about the whole field). One file below 560px.
- **Stewards' notes** (`.session-notes`): a `--surface` panel above the table,
  one verbatim note per line, with the report mark as a quiet pill only when
  it isn't "Official". Cars a note names carry a small amber `.note-flag` in
  the car cell's right-padding gutter (never shifts the tabular digits), the
  note text on hover.
- **Race control** (`.race-control`): a quiet disclosure below the table for
  the flag/RC-message stream. Flag periods are labeled chips (green
  `--res-win` / amber `--accent-tint` tints, colour always paired with the
  flag name + lap + duration); the message log is a framed `--radius-md` list
  in normal flow (the page scrolls, the log doesn't), filterable by car via
  `.rc-car-chip`. Lazy-fetched on first open.

### Combobox (`Combobox.tsx`, `.sep-*`)
The shared typeahead — the `.sp` search grammar as a form field
(`role="combobox"`, `aria-activedescendant`, arrow-key roving, Escape closes
the list, never the host dialog). Matching is case- **and diacritic-**
insensitive over label + hint ("nurburgring" finds Nürburgring), so a hint
carrying the series name means typing either the event or its series narrows.
Two render modes, chosen by the host's clipping context: **inline** (results
in normal flow — a `<dialog>`'s own overflow can never clip them) and
**`floating`** (absolute at `--z-dropdown` with the modal shadow — an overlay
is a state response) for in-page hosts, where an inline list would shove the
content below it on every keystroke. Row vocabulary: muted `auto` default,
accent `+ Create "query"` (offered when the query matches nothing exactly),
and pinned accent **action rows** that survive filtering ("+ New event: …" —
they answer *none of these*). A `maxVisible` cap ends the list with a counted
"n more — keep typing" row, never a silent truncation. Used by the
`SeriesEventPicker` pair in the import modals (inline) and the Imports review
rows (floating), where picking an event from the global list auto-fills its
series — the reviewer types the thing they actually know.

### Import modals (`.uf` / `.ir` / `.cis`)
The Imports page opens two native-`<dialog>` modals on the token layer, siblings
of the search palette (`.sp`) and starting-grid modal (`.sg`): **`UploadFilesModal`
(`.uf`)** — a dashed drag-and-drop dropzone (empty-state vocabulary; drag-over
lifts to the amber accent), a per-file staged queue with a `ImportStatusIcon`
glyph per row, and **`IRacingImportModal` (`.ir`)**. Both pin their target with
the shared **`SeriesEventPicker`** — two inline Comboboxes (series, then
events filtered to it). After
staging, both hand off to the shared **`ConfirmImportStep` (`.cis`)**: event
group cards holding draggable session rows. **Drag feedback is a border/background
token change only — no transform or motion** — so it survives `prefers-reduced-motion`
untouched; every draggable row carries a keyboard-and-touch **"Move to…"
`<select>`** as the equivalent control (WCAG 2.5.7). Selection is amber
(`--accent-tint`), never a class colour. A round-ordinal preview lists the
season's events with `Rd n` chips, new ones in ink and existing ones muted.
**`ImsaEsportsImportModal` (`.ie`)** is a review rather than a picker: the
IMSA Esports correction lays artifactracing.com's classification over imported
iRacing rounds. Each round is a `<details>` that opens itself only where
something needs deciding. Decisions sit in `--surface` groups above a compact
change table: struck-through old values, signed class-place deltas in
`--success`/`--error` that always carry their sign. A pinned footer holds the
status line and the one amber action. Every decision re-plans on the server,
which alone decides when Apply unlocks. Below 640px each table row stacks into
an identity line with its changes underneath.

### Import review rows (`.import-target`)
Each staged batch's confirm strip on the Imports page. Series and Event are
floating Comboboxes (`.target-combo`, 360px) seeded from the parser's guess —
accepting a right guess costs zero keystrokes. **With no series pinned the
event search is global** (hint: series · date, newest first, capped with the
counted overflow row) and picking an event auto-fills its series; pinning a
series narrows the list to it (hint: circuit · date, oldest first), and a
pick that contradicts the other field resets it rather than committing a
mismatch. The series create-row defers creation to commit — discarding the
batch leaves nothing behind — unlike the modal picker, which creates up front
so the new id can pin the staged files. Number inputs (session ordinal) hold
raw text and validate at commit, so "1" can be deleted before "2" is typed —
never clamped on keystroke; the Commit button's tooltip names the first
unmet requirement.

### Inputs / Fields
`--bg` background, `--border-strong` 1px stroke, radius 6px, 6px 10px padding.
Focus is the global amber `--focus-ring`. Errors use `.error-panel`
(error-tint fill, 35% error border) — tokenized, never hardcoded red.

### Widgets (hub summary panels)
`--surface` panels (radius `--radius-lg`, 16px padding) holding **live data
extracts**, never icon+blurb cards: a title row with an amber-ink arrow link,
then divider-separated rows. Aligned variants (`.widget-rows.aligned`) share
column widths via grid so class pills, car numbers, and names sit on common
edges; two-line rows put pill+number in a `.wr-ident` block beside
name-over-detail. A panel holding two short lists (the stat leaders' most wins
and most poles) separates them with a `.widget-mini-head` — an uppercase muted
label, not a second panel — and drops a list entirely when its data is absent
rather than showing a row of zeros.

### Skeletons & Empty States
Loading is `.skeleton` bars (surface-2, 1.4s opacity pulse) shaped like the
content — never centered spinners. Empty states (`.empty-state`) are dashed
`--radius-lg` panels whose copy teaches the fix ("import a standings file from
the Imports tab").

### Theme Toggle
Three-state (Auto/Light/Dark) micro-segmented control in the top bar; persists
to `localStorage('bh-theme')`; `index.html` applies it before first paint.

## 6. Do's and Don'ts

### Do:
- **Do** set every numeric column in JetBrains Mono with `tabular-nums`,
  right-aligned — skimming is the product.
- **Do** keep broadcast amber ≤10% of any screen, solid fills only (the One
  Instrument Rule).
- **Do** pair every meaningful color with a label: class bands print the class
  name, result tints sit under finish numbers, status has text.
- **Do** keep `box-sizing: border-box` on data-grid cells. The sticky identity
  columns cumulate their offsets from the declared widths, so those widths must
  include the padding — otherwise every column sits 20px short and covers its
  neighbour's right edge, which is exactly where the right-aligned digits are.
- **Do** ship per-column sticky offsets as a `--ident-left` custom property, not
  an inline `left`. An inline `left` outranks every stylesheet rule, so the
  narrow-screen media query can never unpin the header.
- **Do** keep data surfaces in normal document flow — no nested scrollboxes.
  Wide tables sit in a `.grid-frame` that hugs their width; if a season
  outgrows the viewport the *document* scrolls horizontally, the way a page
  does. Interior scrolling belongs to modals and nothing else (the user
  rejected scrollboxes-within-the-page explicitly, 2026-07-17).
- **Do** keep motion 70–140ms ease-out-quart, state-triggered, with the
  `prefers-reduced-motion` kill switch already in `index.css`.
- **Do** hold every text pair to WCAG AA on its actual background, both themes.
- **Do** ship skeletons shaped like the content and empty states that point at
  the Imports tab.

### Don't:
- **Don't** build a **generic SaaS dashboard** — no purple gradients,
  hero-metric cards, or icon-in-a-box grids.
- **Don't** go **consumer/gamified** (badges, confetti, oversized friendly
  buttons) or **enterprise/bureaucratic** (heavy chrome, gray-on-gray).
- **Don't** **over-design** — no glassmorphism, no decorative motion, no
  flourish that slows a lookup.
- **Don't** use racing red as an accent, gradients on the amber, side-stripe
  borders, or wide-tracked uppercase eyebrows.
- **Don't** hardcode grays or reds — `#888` and `#e74c3c` were purged; use
  `--text-muted` and `--error`.
- **Don't** invent class or result colors — class colors come from
  `class_style`; result tiers use the four `--res-*` tokens.
- **Don't** treat the sheet's print output as the design target — the
  on-screen surface is the product; print is a scoped `@media print` override,
  never a constraint on the screen design.
- **Don't** gate interaction state behind `requestAnimationFrame` (or a
  transition). rAF is throttled in background tabs and headless renders, so an
  rAF-reset re-entry guard wedges shut and the feature dies silently. Prefer
  idempotent writes that settle on their own.
- **Don't** scroll the page programmatically to "helpfully" reveal a column.
  Grids scroll with the document now, so any auto-scroll shoves the whole page
  sideways on load — and below 700px the identity columns unpin, so it also
  carries the car number and team off-screen and leaves every row anonymous.
