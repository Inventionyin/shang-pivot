import type { MenuNode } from '@/api/auth'
import { NAV_GROUPS, type NavGroup, type NavItem } from '@/config/nav'

export type SidebarGroup = {
  title: string
  items: Array<{
    key: string
    label: string
    path: string
    icon: string
    code?: string
  }>
}

function isVisibleMenu(node: MenuNode): boolean {
  const type = (node.type || '').toUpperCase()
  if (type === 'BUTTON' || type === 'API') return false
  if (node.visible === false) return false
  return true
}

/** 将后端菜单树转为侧栏分组，严格按顶层 sort 顺序。
 * - DIR：分组标题 = 目录名，子 MENU 为链接
 * - 连续顶层 MENU：合并为一组（首段标题「概览」，中间段无标题或用首项名）
 */
export function menusToSidebarGroups(menus?: MenuNode[] | null): SidebarGroup[] {
  if (!menus?.length) return fallbackSidebarGroups()

  const groups: SidebarGroup[] = []
  const sorted = [...menus].sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))

  let orphanBatch: SidebarGroup['items'] = []
  let orphanIsLeading = true

  const flushOrphans = () => {
    if (!orphanBatch.length) return
    // 首段顶层菜单用「概览」；夹在目录之间的顶层菜单不重复标题（侧栏跳过空 title）
    const title = orphanIsLeading ? '概览' : ''
    groups.push({ title, items: orphanBatch })
    orphanBatch = []
    orphanIsLeading = false
  }

  for (const node of sorted) {
    if (!isVisibleMenu(node)) continue
    const type = (node.type || '').toUpperCase()

    if (type === 'DIR') {
      flushOrphans()
      const children = (node.children ?? [])
        .filter(isVisibleMenu)
        .filter((c) => (c.type || '').toUpperCase() === 'MENU' && c.path)
        .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
        .map((c) => ({
          key: c.code || String(c.id),
          label: c.name,
          path: c.path!,
          icon: c.icon || 'Menu',
          code: c.code,
        }))
      if (children.length) {
        groups.push({ title: node.name, items: children })
      }
      orphanIsLeading = false
      continue
    }

    if (type === 'MENU' && node.path) {
      orphanBatch.push({
        key: node.code || String(node.id),
        label: node.name,
        path: node.path,
        icon: node.icon || 'Menu',
        code: node.code,
      })
    }
  }
  flushOrphans()

  return groups.length ? groups : fallbackSidebarGroups()
}

export function fallbackSidebarGroups(): SidebarGroup[] {
  return NAV_GROUPS.map((g: NavGroup) => ({
    title: g.title,
    items: g.items.map((item: NavItem) => ({
      key: item.key,
      label: item.label,
      path: item.path,
      icon: item.icon,
      code: item.key,
    })),
  }))
}

export type FlatPermNode = {
  id: string
  parentId: string
  code: string
  name: string
  type: string
  path: string
  component: string
  icon: string
  sort: number
  visible: boolean
  status: string
  children: FlatPermNode[]
  depth: number
}

export function mapPermissionTree(
  nodes: Record<string, unknown>[],
  depth = 0,
): FlatPermNode[] {
  return nodes
    .map((n) => {
      const children = Array.isArray(n.children)
        ? mapPermissionTree(n.children as Record<string, unknown>[], depth + 1)
        : []
      return {
        id: String(n.id ?? ''),
        parentId: String(n.parentId ?? 0),
        code: String(n.code ?? ''),
        name: String(n.name ?? ''),
        type: String(n.type ?? 'MENU'),
        path: String(n.path ?? ''),
        component: String(n.component ?? ''),
        icon: String(n.icon ?? ''),
        sort: Number(n.sort ?? 0),
        visible: n.visible !== false,
        status: String(n.status ?? 'ENABLED'),
        children,
        depth,
      }
    })
    .sort((a, b) => a.sort - b.sort)
}

/** 树展开为可见行（按 expanded 集合） */
export function flattenVisibleTree(
  nodes: FlatPermNode[],
  expanded: Set<string>,
): FlatPermNode[] {
  const out: FlatPermNode[] = []
  const walk = (list: FlatPermNode[]) => {
    for (const n of list) {
      out.push(n)
      if (n.children.length && expanded.has(n.id)) {
        walk(n.children)
      }
    }
  }
  walk(nodes)
  return out
}

export function collectTreeIds(nodes: FlatPermNode[]): string[] {
  const ids: string[] = []
  const walk = (list: FlatPermNode[]) => {
    for (const n of list) {
      ids.push(n.id)
      if (n.children.length) walk(n.children)
    }
  }
  walk(nodes)
  return ids
}

export function collectExpandableIds(nodes: FlatPermNode[]): string[] {
  const ids: string[] = []
  const walk = (list: FlatPermNode[]) => {
    for (const n of list) {
      if (n.children.length) {
        ids.push(n.id)
        walk(n.children)
      }
    }
  }
  walk(nodes)
  return ids
}
