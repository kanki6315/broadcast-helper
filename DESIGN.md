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

### Functional — Result Tints (recap cells)
- **Win** (green tint), **Top 3** (pink tint), **Top 5** (violet tint): fills
  behind start→finish numbers, tuned per theme so default ink stays AA on top.
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
  (team-sheets PDF modal).
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

### Data Grid (the signature component — `.grid-table` in `.grid-scroll`)
The heart of the tool: recap, standings, lineups, results. Hairline row
dividers, `border-collapse: separate`, sticky headers (surface background) and
sticky identity columns with explicit left offsets (disabled below 700px),
scroll contained in a bordered `--radius-md` container capped at 80vh. Round
columns show a mono venue code over a muted "Rd n". Class sections divide on a
**class band** — a full-width row filled with the class color, name always
printed on it. Result cells stack one `.race-line` per race, tinted by finish
tier, with the amber **P** for pole and DNS/skips as quiet muted marks.

### Buttons
- **Shape:** `--radius-md` (6px), 6px 14px padding, label type.
- **Primary:** solid amber, `--on-accent` ink; hover mixes 12% ink into the
  fill. **Secondary/default:** `--surface` with `--border-strong`, hover
  `--surface-2`. **Disabled:** 50% opacity, `not-allowed` cursor.

### Segmented Control (`.seg` / `.seg-btn`)
The filter vocabulary (championship/cup, teams/drivers, sub-nav): a
`--surface` pill-box (radius 6px, 2px padding); the active segment lifts on
`--bg` with `--shadow-raise` and ink text. Overflows scroll invisibly within
the pill — never the page.

### Chips
- **Class filter chip** (`.class-chip`): pill outline + 10px color swatch +
  class code; active fills 12% of the class color and borders in it.
- **Class tag** (`.class-tag`): solid class-color block, white 700 text —
  the inline class marker in tables and widgets.
- **Round chip** (`.round-chip`): venue code over "Rd n"; active = amber
  border + `--accent-tint` fill.

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
name-over-detail.

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
- **Do** contain wide tables in their own `.grid-scroll` — the page never
  scrolls horizontally.
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
- **Don't** treat the print-first PDF sheet as the design target — the
  on-screen surfaces are the product.
- **Don't** gate interaction state behind `requestAnimationFrame` (or a
  transition). rAF is throttled in background tabs and headless renders, so an
  rAF-reset re-entry guard wedges shut and the feature dies silently. Prefer
  idempotent writes that settle on their own.
- **Don't** auto-scroll a data grid on narrow screens. Below 700px the identity
  columns unpin, so scrolling right carries the car number and team off-screen
  and leaves every row anonymous.
