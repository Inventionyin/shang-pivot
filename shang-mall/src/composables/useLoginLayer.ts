import { ref } from 'vue'
import { getAccessToken } from '@/utils/request'

/**
 * 登录层（PRD §8.1 弹窗与提示 / §8.5：「未登录点加购 / 购买 / 结算 / 个人中心」）。
 * Web 居中对话框、H5 底部半屏；登录成功后回到原场景。
 */
const visible = ref(false)

/** 同一时刻只保留一个待决 Promise：重复调用复用同一次登录结果 */
let waiters: Array<(ok: boolean) => void> = []

export const loginLayerVisible = visible

/** 已登录直接返回 true；否则拉起登录层，等待用户登录或取消 */
export function openLoginLayer(): Promise<boolean> {
  if (getAccessToken()) return Promise.resolve(true)
  visible.value = true
  return new Promise<boolean>((resolve) => {
    waiters.push(resolve)
  })
}

/** 关闭登录层并通知所有等待者（登录成功 true / 取消 false） */
export function settleLoginLayer(ok: boolean) {
  visible.value = false
  const pending = waiters
  waiters = []
  pending.forEach((resolve) => resolve(ok))
}