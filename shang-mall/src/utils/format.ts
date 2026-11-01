export function newIdempotentKey(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function maskPhone(phone?: string | null): string {
  if (!phone || phone.length < 7) return phone || ''
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`
}

export function formatPrice(n?: number | string | null): string {
  const v = Number(n ?? 0)
  if (Number.isNaN(v)) return '0.00'
  return v.toFixed(2)
}
