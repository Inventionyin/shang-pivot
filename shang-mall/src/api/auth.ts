import { http } from '@/utils/request'
import { detectClientType } from '@/composables/useClientType'

export interface CustomerProfile {
  id: string
  phone?: string | null
  email?: string | null
  nickname?: string | null
  avatar?: string | null
  status: 'NORMAL' | 'FROZEN' | string
}

export interface LoginResult {
  accessToken: string
  refreshToken: string
  expiresIn: number
  customer: CustomerProfile
}

/** 后端 ErrorCode.LOGIN_FAILED：验证码错误或已过期（PRD §8.1 内联错误） */
export const LOGIN_FAILED_CODE = 40101
/** 后端 ErrorCode.CUSTOMER_FROZEN：账号不可用 */
export const CUSTOMER_FROZEN_CODE = 40310

/** 发送邮箱验证码（真发；随机 6 位，5 分钟有效，60 秒冷却） */
export function sendEmailCode(email: string) {
  return http<{ cooldownSec: number }>({
    url: '/api/auth/email/send',
    method: 'POST',
    data: { email },
    silent: true,
  })
}

/** 邮箱验证码登录（未注册自动注册） */
export function loginByEmail(email: string, code: string) {
  return http<LoginResult>({
    url: '/api/auth/login/email',
    method: 'POST',
    data: { email, code, clientType: detectClientType() },
    silent: true,
  })
}

export function sendSms(phone: string) {
  return http<{ cooldownSec: number }>({
    url: '/api/auth/sms/send',
    method: 'POST',
    data: { phone },
    silent: true,
  })
}

/** 登录失败由调用方内联展示（验证码错误等），不走统一 Toast */
export function loginByPhone(phone: string, code: string) {
  return http<LoginResult>({
    url: '/api/auth/login',
    method: 'POST',
    data: { phone, code, clientType: detectClientType() },
    silent: true,
  })
}

export function logoutApi() {
  return http<null>({ url: '/api/auth/logout', method: 'POST' })
}

export function fetchMe() {
  return http<CustomerProfile>({ url: '/api/me', method: 'GET' })
}

export function updateMe(data: { nickname?: string; avatar?: string }) {
  return http<CustomerProfile>({ url: '/api/me', method: 'PUT', data })
}
