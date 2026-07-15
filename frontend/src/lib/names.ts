/** Short display name: "Jack Aitken" → "J. Aitken". Single-word names pass through. */
export function shortName(name: string): string {
  const parts = name.trim().split(/\s+/)
  if (parts.length < 2) return name
  return `${parts[0][0]}. ${parts.slice(1).join(' ')}`
}
