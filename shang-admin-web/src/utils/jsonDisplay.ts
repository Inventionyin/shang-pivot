/** 详情页 JSON / 文本展示工具 */

export function escapeHtml(input: string): string {
  return input
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export type JsonDisplay = {
  text: string
  html: string
  isJson: boolean
  empty: boolean
}

function highlightJson(pretty: string): string {
  const escaped = escapeHtml(pretty)
  return escaped.replace(
    /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
    (match, str: string | undefined, colon: string | undefined, keyword: string | undefined) => {
      if (str != null) {
        if (colon != null) {
          return `<span class="json-key">${str}</span>${colon}`
        }
        return `<span class="json-str">${str}</span>`
      }
      if (keyword != null) {
        return `<span class="json-kw">${keyword}</span>`
      }
      return `<span class="json-num">${match}</span>`
    },
  )
}

/** 将任意值格式化为可读展示（JSON 优先美化） */
export function toJsonDisplay(value: unknown): JsonDisplay {
  if (value == null) {
    return { text: '—', html: '—', isJson: false, empty: true }
  }

  if (typeof value === 'object') {
    try {
      const text = JSON.stringify(value, null, 2)
      return { text, html: highlightJson(text), isJson: true, empty: false }
    } catch {
      const text = String(value)
      return { text, html: escapeHtml(text), isJson: false, empty: !text }
    }
  }

  const raw = String(value).trim()
  if (!raw || raw === '—' || raw === 'null' || raw === 'undefined') {
    return { text: '—', html: '—', isJson: false, empty: true }
  }

  try {
    const parsed = JSON.parse(raw) as unknown
    const text = JSON.stringify(parsed, null, 2)
    return { text, html: highlightJson(text), isJson: true, empty: false }
  } catch {
    return { text: raw, html: escapeHtml(raw), isJson: false, empty: false }
  }
}
