---
name: Broadcast Helper
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

# Design System: Broadcast Helper

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
tier, with the amber **P** for pole and DNS/skips as quiet muted marks.

The **standings** grid prints how each round paid, not one summed number: a
`.pts-cell` stacks one `.pts-line` per scoring session (muted `Q`/`R1` tags on
multi-session rounds only), race points right-aligned in the digit column and
every extra spelled out in a shared marks gutter — amber `+1P` pole, ink `+1F`
fastest lap, a bare muted `+10` for a PDF's lumped bonus (no letter code: the
source doesn't say pole or fastest lap), error-red `−n` penalty —
so the arithmetic sits on the table, not in a tooltip (the line's title still
gives the session total; the cell's title the round total). Zero stays printed
but recedes; a did-not-run session is a muted `·`; a skipped round keeps the
single `—`. Single-session rounds print the bare number, so plain-points
leagues look unchanged, and the legend (built from the shown data, not static)
only decodes marks that actually appear — or vanishes entirely.

### Stats Table (`.stats-table`, a Data Grid variant)
Per-driver tallies split by race format: a **two-row header** (format group
name over its St / W / P3 / T5 / DNF sub-columns), a trailing Qualifying group
(Pole / T5) only where quali data exists, and a hairline `.grp-start` opening
each group so five-wide runs of digits stay scannable. Non-zero win, podium
and top-5 counts wear the recap's result tints as `.stat-chip`s; zeros recede
to `--text-muted` at reduced opacity, and a format a driver never contested
prints "·", not 0 — never entered and finished-nowhere are different facts.
This grid runs **denser than the standard Data Grid** (tighter cell padding,
narrower chips, centered values) because up to six column groups have to fit
one screen; the ident columns keep normal padding so names don't crowd. Class
sections use the same class band as the recap.

### Event Sheet (`frontend/src/pages/sheet.css`)
The standalone per-event reference (`/sheet/:eventId`), on the same token
layer and result vocabulary as the recap: class bands with computed ink, one
`tbody` per entry (main row + season form strip), zebra as the class colour
mixed 8% into `--bg`, `.race-line` chips for start/finish. The Start column
carries the short-form starting driver ("H. Grisham") under the grid slot
where a grid file named one. Rows deep-link to
the team-sheets modal (the car number is a real button for keyboard reach);
prior-year cells are contentEditable and save on blur. Its `@media print`
block forces the light token values on the `.sheet` scope, so Print/Save-PDF
emits the compact US-Letter deliverable from either theme. Manufacturer
wordmark logos sit on a small white chip in dark mode only.

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
reshapes the table. On a qualifying session the driver column names the one
driver the session credits, and its header says which claim that is:
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

### Import modals (`.uf` / `.ir` / `.cis`)
The Imports page opens two native-`<dialog>` modals on the token layer, siblings
of the search palette (`.sp`) and starting-grid modal (`.sg`): **`UploadFilesModal`
(`.uf`)** — a dashed drag-and-drop dropzone (empty-state vocabulary; drag-over
lifts to the amber accent), a per-file staged queue with a `ImportStatusIcon`
glyph per row, and **`IRacingImportModal` (`.ir`)**. Both pin their target with
the shared **`SeriesEventPicker`** — two typeahead comboboxes (series, then
events filtered to it) built on the `.sp` search grammar (`role="combobox"`,
`aria-activedescendant`, arrow-key roving); its results render **in normal flow,
not `position:absolute`**, so the dialog's own overflow never clips them. After
staging, both hand off to the shared **`ConfirmImportStep` (`.cis`)**: event
group cards holding draggable session rows. **Drag feedback is a border/background
token change only — no transform or motion** — so it survives `prefers-reduced-motion`
untouched; every draggable row carries a keyboard-and-touch **"Move to…"
`<select>`** as the equivalent control (WCAG 2.5.7). Selection is amber
(`--accent-tint`), never a class colour. A round-ordinal preview lists the
season's events with `Rd n` chips, new ones in ink and existing ones muted.

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
