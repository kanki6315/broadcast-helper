# Product

## Register

product

## Users

A motorsports broadcaster preparing for and calling live events — currently the
primary (and sole) user, a hands-on operator who knows the data cold. Used in
three contexts, all of which matter:

- **Desk prep before an event** — importing results, standings, grids, and
  entry lists; matching car photos and logos; assembling each event's reference
  sheet. Throughput and clean data entry dominate here.
- **Live, glancing while on air** — pulling up a fact mid-broadcast under time
  pressure. Fast lookup and glanceability dominate here.
- **At the track / in a booth** — variable and often low lighting. Dark-mode
  legibility and contrast robustness matter.

The job to be done: get to a trustworthy fact fast — a driver's rating, a car's
season form, a championship position, a win or pole count — without hunting.

## Product Purpose

Broadcast Helper turns raw timing-provider exports (results, standings, grids,
entry-list and championship-points PDFs) into a browsable, broadcast-ready
reference: championships
grouped by family, a per-class season reference table, per-driver counting
stats (wins, podiums, poles — split by the kind of race, because a sprint win
and a six-car heat win are different facts), and a per-event sheet
with drivers, ratings, qualifying, prior-year-at-venue, championship position,
and season form.

**The on-screen sheet is the product.** The print-to-PDF export was a
proof-of-concept and is not the long-term deliverable — future work should treat
the live, on-screen surfaces as the real output, not a preview of a PDF. Success
is measured by how quickly and confidently the broadcaster can find and trust a
fact while prepping or on air.

## Brand Personality

Broadcast-grade and polished. Three words: **crisp, authoritative, skimmable.**

The target feel is a fusion of two references the user named explicitly:

- **Apple-clean restraint** (Linear / Raycast / macOS pro tools) — immaculate
  spacing, subtle depth, one confident accent, every element considered but
  understated. Nothing decorative, nothing shouting.
- **On-air timing / data-terminal density** (live race-timing leaderboards) —
  tight rows, tabular numbers, class colors that carry meaning, authoritative
  data-forward presentation.

The synthesis: the calm and craft of Apple applied to the density and
data-authority of a timing terminal. Above all, the interface must let the user
**skim** — a season recap table, a standings board, an event sheet — and land on
the fact instantly.

## Anti-references

The user rejected all four of the common traps. Do not let the interface drift
toward any of them:

- **Generic SaaS dashboard** — purple gradients, hero-metric cards, rounded
  icon-in-a-box grids, marketing-y chrome. The default AI look.
- **Consumer / gamified** — playful colors, badges, confetti, oversized friendly
  buttons. Too casual for a professional reference tool.
- **Enterprise / bureaucratic** — heavy chrome, cluttered toolbars, gray-on-gray,
  Bootstrap-era density-without-care.
- **Over-designed / decorative** — glassmorphism, animation for its own sake,
  flourishes that slow down lookup.

## Design Principles

1. **Skim beats read.** The core job is fast lookup under pressure. Tabular
   numbers, clear column rhythm, meaningful class colors, and a strong scanning
   hierarchy come before everything else. If a change makes a table harder to
   skim, it is wrong regardless of how it looks.
2. **Invisible, earned familiarity.** Use standard affordances (tabs, tables,
   nav) tuned to Apple-grade precision; never reinvent controls for flavor. The
   tool should disappear into the task.
3. **Density with clarity, not density with clutter.** Embrace timing-terminal
   information density, but earn it with spacing, alignment, and typographic
   order — the opposite of the bureaucratic anti-reference.
4. **Dark mode is first-class.** The booth and the track are real environments;
   dark mode is a primary surface to be tuned deliberately, not a `prefers-color-scheme`
   afterthought.
5. **Data authority.** Correctness must be legible: numbers align, class colors
   are consistent and meaningful, nothing invites a second-guess mid-broadcast.

## Accessibility & Inclusion

- **Robust dark mode** is the confirmed environment priority — genuinely legible
  in low light, not a tint of the light theme.
- **Contrast floor: WCAG AA.** Body text ≥ 4.5:1, large/bold text ≥ 3:1, against
  its actual background. No washed-out gray-on-tint body copy.
- **Reduced motion** respected by default — any motion added must ship with a
  `prefers-reduced-motion` alternative (motion here is state feedback only, never
  decoration).
- **Consideration:** motorsport class colors can collide for color-vision
  deficiency. Where a class color or status carries meaning, pair it with a label
  or code rather than relying on hue alone.
