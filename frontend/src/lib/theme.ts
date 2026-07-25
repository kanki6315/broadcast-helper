// Three-state theme: 'system' follows prefers-color-scheme (no attribute),
// 'light'/'dark' force via data-theme on <html>. index.html applies the stored
// value before first paint; this module owns changes after that.

export type Theme = 'system' | 'light' | 'dark'

const KEY = 'bh-theme'

export function getTheme(): Theme {
  const v = localStorage.getItem(KEY)
  return v === 'light' || v === 'dark' ? v : 'system'
}

export function setTheme(theme: Theme): void {
  const root = document.documentElement

  // A `transition` on a colour that resolves through a theme token LATCHES when
  // the token changes: the element keeps rendering the previous theme's value
  // until something re-triggers it. Measured before this guard: the toggle's own
  // "Light" label sat at 1.2:1 on the dark surface, and muted nav labels at
  // 2.5:1 — both stuck at light-theme ink on a dark page.
  //
  // Suppress transitions for the swap, then restore. The two forced reflows are
  // deliberate and synchronous: rAF is throttled in background tabs and headless
  // renders, so scheduling the cleanup would leave the guard stuck on.
  root.setAttribute('data-theme-switching', '')
  void root.offsetHeight

  if (theme === 'system') {
    localStorage.removeItem(KEY)
    root.removeAttribute('data-theme')
  } else {
    localStorage.setItem(KEY, theme)
    root.setAttribute('data-theme', theme)
  }

  void root.offsetHeight
  root.removeAttribute('data-theme-switching')
}
