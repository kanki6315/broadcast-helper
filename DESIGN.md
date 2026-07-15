<!-- SEED: re-run /impeccable document once the visual system is built, to capture the actual tokens and components and generate the .impeccable/design.json sidecar. -->
---
name: Broadcast Helper
description: A skim-first motorsport reference — Apple-clean restraint fused with on-air timing-terminal density.
---

# Design System: Broadcast Helper

## 1. Overview

**Creative North Star: "The Timing Tower"**

The trackside timing tower shows the running order at a glance — authoritative,
unadorned, instantly legible from a distance and under pressure. That is the
whole system in one image. Broadcast Helper is an instrument for reading facts
fast: during desk prep, live on air, and trackside in a dim booth. Every surface
earns its place by how quickly the broadcaster lands on the number they need.

The character is a deliberate fusion of two worlds the product owner named
explicitly: **Apple-clean restraint** (Linear / Raycast / macOS pro tools —
immaculate spacing, one confident accent, nothing decorative) and **on-air
timing-terminal density** (tight rows, tabular numbers, meaningful class colors,
data-forward authority). The synthesis is the calm and craft of the former
applied to the density and legibility of the latter. Dark mode is not a variant
here; it is a primary surface, tuned for low-light booths.

This system explicitly rejects the four traps the owner ruled out: the **generic
SaaS dashboard** (purple gradients, hero-metric cards, rounded-icon grids), the
**consumer / gamified** look (badges, confetti, oversized friendly buttons), the
**enterprise / bureaucratic** look (heavy chrome, gray-on-gray, clutter), and the
**over-designed / decorative** (glassmorphism, motion for its own sake). If a
change makes a table harder to skim, it is wrong regardless of how it looks.

**Key Characteristics:**
- Skim-first: tabular numerals, clear column rhythm, strong scanning hierarchy.
- Restrained chrome, one warm accent (broadcast amber), functional class colors.
- Dark-mode-first, WCAG AA floor, legible in a booth.
- Fast, understated motion (50–100ms) — state feedback, never choreography.
- Invisible, earned familiarity: standard affordances at instrument-grade precision.

## 2. Colors

A near-neutral, dark-mode-first surface carrying a single warm broadcast accent,
with a separate functional palette reserved for motorsport class colors.
*(Seed anchors below are committed; the full ramp, semantic-state set, and class
palette resolve during implementation and the next scan-mode pass.)*

### Primary
- **Broadcast Amber** (light `#B8791F`, dark `#F0B84A`): the one confident accent.
  Primary actions, the current/active tab, focus and selection, and the live
  indicator. Warm and on-air, never racing red. Used sparingly — see the One
  Instrument Rule.

### Neutral
- **Ink** (light `#08060D` headings / `#3D3A45` body; dark `#F3F4F6` headings /
  `#B6BCC7` body): text ramp. Body must clear WCAG AA (≥4.5:1) on its actual
  surface — no washed-out gray-on-tint.
- **Surface** (light `#FFFFFF`; dark `#16171D`): the content ground. A second,
  slightly cooler panel tone for toolbars / side surfaces resolves in implementation.
- **Divider** (light `#E5E4E7`; dark `#2E303A`): table rules, borders, hairlines —
  the quiet grid the timing data sits on.

### Functional — Class Palette (reserved, not decorative)
- Motorsport class colors are **data**, driven per series by `class_style`
  (`--class-color` / `--class-tint`), not chosen for aesthetics. They must never
  be reused as UI accent. Because class hues can collide for color-vision
  deficiency, class color is always paired with the class **code/label**, never
  hue alone. `[exact tints to be resolved during implementation]`

### Semantic States
`[hover / focus / active / selected / disabled / error / warning / success — to
be resolved during implementation. Standardize once, apply everywhere. Error must
be tokenized and AA-compliant, replacing the current hardcoded #e74c3c / #888.]`

### Named Rules
**The One Instrument Rule.** Broadcast amber is the *only* voiced accent, on ≤10%
of any screen — primary action, current selection, live state. Its scarcity is
what makes it read as an instrument light and not decoration.

**The Color-Is-Data Rule.** Any color that carries meaning (class, status) is
always paired with a label or code. Hue never encodes information alone.

**The Solid-Fill Rule.** No gradients on the accent, ever. A gradient amber
button is the SaaS trap; a solid one is a broadcast instrument.

## 3. Typography

**Body / UI Font:** one neutral humanist-or-grotesque sans, all chrome, labels,
and prose. `[family to be chosen at implementation; system-ui is the current
placeholder]`
**Data / Mono Font:** a monospaced or tabular-figure cut for numbers, lap/qual
times, positions, and any aligned numeric column. `[family to be chosen at
implementation]`

**Character:** one restrained sans doing the talking, with numerals that lock
into columns so the eye scans straight down a results or reference table. No
display face — identity comes from spacing, alignment, and the amber accent, not
from letterforms.

### Hierarchy
*(Fixed rem scale — product UI, viewed at consistent DPI. Not fluid/clamp. Tight
1.125–1.2 ratio between steps to keep dense screens calm. Exact values resolve in
implementation.)*
- **Headline** (600, page title): `[size TBD]`. One per view; `letter-spacing:-0.02em`.
- **Title** (600, section / table caption): `[size TBD]`.
- **Body** (400, prose and labels): `[~16px TBD]`. Prose capped at 65–75ch; dense
  tables may run wider.
- **Data** (mono / tabular, numeric cells): `font-variant-numeric: tabular-nums`,
  right-aligned in numeric columns. `[size TBD]`
- **Label** (500–600, compact UI labels, badges): `[small size TBD]`. Sentence or
  code case — **not** wide-tracked all-caps eyebrows.

### Named Rules
**The Tabular Rule.** Every number that lives in a column uses tabular figures and
aligns on the decimal. Positions, times, points, car numbers — they must scan as a
straight vertical read.

**The No-Eyebrow Rule.** No tiny uppercase wide-tracked kicker above sections.
Hierarchy comes from size and weight, not from decorative labels.

## 4. Elevation

Flat by default. Depth is conveyed through the hairline divider grid and the
second surface tone, not through shadows — a timing screen has no drop shadows.
Elevation appears only as a **response to state**: a hovered clickable row, an
open modal, a focused control. `[exact shadow / overlay tokens to be resolved
during implementation; keep them tight and low-contrast.]`

### Named Rules
**The Flat-Instrument Rule.** Surfaces are flat at rest. If a shadow appears with
no state change behind it (hover, focus, overlay), it is decoration — remove it.

## 5. Components

*(Direction for the surfaces to build; the current bare CSS is a throwaway
baseline, not the spec. Exact values resolve in implementation and the next scan
pass.)*

### Data Table (the signature component)
The heart of the tool — results, standings, the season reference grid. Dense,
quiet, skim-first: hairline row dividers, tabular numerals, sticky headers on
long/ wide tables, horizontal scroll contained (never breaking page layout).
Clickable rows show a fast state change on hover; class color sits in a labeled
cell, never as a bare stripe. No zebra-stripe noise unless density genuinely
demands it.

### Buttons
- **Shape:** small, tight radius `[TBD]`. **Primary:** solid broadcast amber,
  ink-on-amber text meeting AA. **Secondary / ghost:** neutral, divider-bordered.
- **States:** default / hover / focus-visible / active / disabled — all defined,
  none skipped. Fast (50–100ms) transitions.

### Tabs (top navigation)
Text tabs with an amber underline on the active tab (the current pattern, kept and
refined). Active = amber accent + weight; inactive = body ink. Focus-visible ring
required.

### Inputs / Fields
Divider-stroked, surface background, tight radius. Focus is an amber ring/border
shift, not a glow. Error and disabled states tokenized (replacing hardcoded reds).

### Season Reference / Timing Grid
The densest surface: rows = cars, columns = rounds, cell = start→finish in class.
Must stay readable at high density — tabular figures, class color + code, contained
horizontal scroll, sticky first column and header.

## 6. Do's and Don'ts

### Do:
- **Do** make every numeric column tabular and aligned — skimming a results table
  is the core job.
- **Do** keep broadcast amber to ≤10% of any screen (the One Instrument Rule), as
  a solid fill only.
- **Do** tune dark mode as a first-class surface — legible in a low-light booth,
  not a tint of the light theme.
- **Do** hold body text to WCAG AA (≥4.5:1) on its real background; replace the
  current hardcoded `#888` / `#e74c3c` with tokenized, AA-compliant values.
- **Do** pair any meaningful color (class, status) with a label or code — never
  hue alone.
- **Do** keep motion fast and understated (50–100ms), state-only, with a
  `prefers-reduced-motion` fallback.

### Don't:
- **Don't** build a **generic SaaS dashboard** — no purple gradients, hero-metric
  cards, or rounded-icon-in-a-box grids.
- **Don't** go **consumer / gamified** — no badges, confetti, or oversized friendly
  buttons.
- **Don't** go **enterprise / bureaucratic** — no heavy chrome, gray-on-gray, or
  cluttered toolbars.
- **Don't** **over-design** — no glassmorphism, no decorative motion, no flourish
  that slows a lookup.
- **Don't** use racing red as the accent (first-order motorsport cliché) or a
  gradient on the amber (the SaaS trap).
- **Don't** put a wide-tracked uppercase eyebrow above sections, or a
  `border-left` colored stripe on rows/cards.
- **Don't** treat the print-first PDF sheet as the design target — the on-screen
  surfaces are the product now.
