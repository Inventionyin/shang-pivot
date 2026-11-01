/**
 * 库存预警档位 —— 全后台唯一定义处。
 *
 * 口径（PRD-V1.0 §8.12「库存查询」）：
 *   缺货   ：可售 ≤ 0
 *   低库存 ：0 < 可售 ≤ 阈值
 *   关注   ：阈值 < 可售 ≤ 阈值 × 1.5
 *   正常   ：可售 > 阈值 × 1.5
 *
 * 工作台与库存管理必须共用本函数，否则同一份数据会出现两种档位（历史上就出现过：
 * 工作台把阈值以上全标成「关注」，库存管理则完全没有「关注」档）。
 */
export type StockTier = '缺货' | '低库存' | '关注' | '正常'

/** 状态徽标色调，与 StatusBadge 的 tone 对齐 */
export type StockTierTone = 'brick' | 'amber' | 'forest'

export const WATCH_RATIO = 1.5

export function stockTier(available: number, threshold: number): StockTier {
  if (available <= 0) return '缺货'
  if (available <= threshold) return '低库存'
  if (available <= threshold * WATCH_RATIO) return '关注'
  return '正常'
}

export function stockTierTone(tier: StockTier): StockTierTone {
  if (tier === '低库存' || tier === '缺货') return 'brick'
  if (tier === '关注') return 'amber'
  return 'forest'
}

/** 是否为需要提醒补货的档位（低库存 / 关注 / 缺货） */
export function isWarnTier(tier: StockTier): boolean {
  return tier !== '正常'
}