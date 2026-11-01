/**
 * 将 OSS 地址转为浏览器可访问的地址。
 * MinIO 私有桶直链会 403；统一走后端代理 /admin/files/view。
 */
export function resolveMediaUrl(url: string | null | undefined): string {
  if (!url) return ''
  const raw = url.trim()
  if (!raw) return ''
  if (raw.startsWith('/admin/files/view')) return raw
  if (raw.startsWith('blob:') || raw.startsWith('data:')) return raw
  // 纯 objectKey（无协议）走后端代理
  if (!raw.includes('://') && !raw.startsWith('/')) {
    return `/admin/files/view?objectKey=${encodeURIComponent(raw)}`
  }

  // http(s)://{host}/{bucket}/{objectKey...}
  try {
    const u = new URL(raw, window.location.origin)
    const parts = u.pathname.replace(/^\/+/, '').split('/')
    if (parts.length >= 2 && (u.port === '9000' || u.hostname.includes('minio') || parts[0])) {
      // 约定 path 第一段为 bucket
      const objectKey = parts.slice(1).join('/')
      if (objectKey) {
        return `/admin/files/view?objectKey=${encodeURIComponent(objectKey)}`
      }
    }
  } catch {
    /* keep raw */
  }
  return raw
}
