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
  if (theme === 'system') {
    localStorage.removeItem(KEY)
    document.documentElement.removeAttribute('data-theme')
  } else {
    localStorage.setItem(KEY, theme)
    document.documentElement.setAttribute('data-theme', theme)
  }
}
