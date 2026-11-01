/** 把对象键 / 管理端代理路径转成 C 端可访问的图片 URL。 */
export function resolveMediaUrl(url: string | null | undefined): string {
  if (!url) return ''
  const raw = String(url).trim()
  if (!raw) return ''
  if (raw.startsWith('blob:') || raw.startsWith('data:')) return raw
  if (raw.startsWith('/api/files/view') || raw.startsWith('/admin/files/view')) return raw
  if (raw.startsWith('http://') || raw.startsWith('https://')) {
    try {
      const u = new URL(raw)
      const parts = u.pathname.replace(/^\//, '').split('/')
      if (parts.length >= 2) {
        const objectKey = parts.slice(1).join('/')
        if (objectKey) return `/api/files/view?objectKey=${encodeURIComponent(objectKey)}`
      }
    } catch {
      /* ignore */
    }
    return raw
  }
  // 纯 objectKey
  if (!raw.includes('://') && !raw.startsWith('/')) {
    return `/api/files/view?objectKey=${encodeURIComponent(raw)}`
  }
  return raw
}
