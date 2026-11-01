import { useAuthStore } from '@/stores/auth'

/** 超级管理员角色码（拥有全部权限时免检） */
const SUPER_ROLE_CODES = new Set(['SUPER_ADMIN', 'ADMIN', 'admin'])

export function usePermission() {
  const auth = useAuthStore()

  function permissionSet(): Set<string> {
    return new Set(auth.user?.permissions ?? [])
  }

  function roleSet(): Set<string> {
    return new Set(auth.user?.roles ?? [])
  }

  function isSuperAdmin(): boolean {
    const roles = roleSet()
    for (const code of SUPER_ROLE_CODES) {
      if (roles.has(code)) return true
    }
    // 兼容：拥有菜单管理权限码时通常是超管种子角色
    const perms = permissionSet()
    return perms.has('*')
  }

  function hasPermission(code?: string | string[] | null): boolean {
    if (!code || (Array.isArray(code) && code.length === 0)) return true
    if (isSuperAdmin()) return true
    const set = permissionSet()
    if (set.size === 0) return false
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((c) => set.has(c))
  }

  function hasAnyPermission(...codes: string[]): boolean {
    return hasPermission(codes)
  }

  return { hasPermission, hasAnyPermission, isSuperAdmin, permissionSet }
}

/** 非 setup 场景（路由守卫） */
export function hasPermission(code?: string | string[] | null): boolean {
  const auth = useAuthStore()
  if (!code || (Array.isArray(code) && code.length === 0)) return true
  const roles = new Set(auth.user?.roles ?? [])
  if ([...SUPER_ROLE_CODES].some((c) => roles.has(c))) return true
  const set = new Set(auth.user?.permissions ?? [])
  if (set.has('*')) return true
  const codes = Array.isArray(code) ? code : [code]
  return codes.some((c) => set.has(c))
}
