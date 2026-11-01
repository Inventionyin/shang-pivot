import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { showToast } from 'vant'

export interface ApiResult<T = unknown> {
  code: number
  msg: string
  data: T
  traceId?: string
}

/** silent：由调用方自行内联展示错误（如登录层的验证码错误），不弹 Toast */
export interface ApiRequestConfig extends AxiosRequestConfig {
  silent?: boolean
}

const ACCESS_KEY = 'shang_mall_access'
const REFRESH_KEY = 'shang_mall_refresh'

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY)
}

export function setTokens(access: string, refresh?: string) {
  localStorage.setItem(ACCESS_KEY, access)
  if (refresh) localStorage.setItem(REFRESH_KEY, refresh)
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

const request: AxiosInstance = axios.create({
  baseURL: '/',
  timeout: 30000,
})

request.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const params = config.params as Record<string, unknown> | undefined
  if (params && params.pageSize != null && params.size == null) {
    params.size = params.pageSize
    delete params.pageSize
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResult
    if (payload && typeof payload.code === 'number' && payload.code !== 0 && payload.code !== 200) {
      if (!(response.config as ApiRequestConfig).silent) {
        showToast(payload.msg || '请求失败')
      }
      return Promise.reject(payload)
    }
    return response
  },
  (error) => {
    const status = error.response?.status as number | undefined
    if (status === 401) {
      clearTokens()
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = `/login?redirect=${redirect}`
      }
      return Promise.reject(error)
    }
    if ((error.config as ApiRequestConfig | undefined)?.silent) {
      return Promise.reject(error)
    }
    const data = error.response?.data
    const msg =
      (typeof data === 'string' && data.includes('CORS')
        ? '跨域被拦截，请检查后端 CORS 配置或重启后端'
        : null) ||
      data?.msg ||
      error.message ||
      '网络异常，请稍后重试'
    showToast(msg)
    return Promise.reject(error)
  },
)

export async function http<T = unknown>(config: ApiRequestConfig): Promise<ApiResult<T>> {
  const res = await request.request<ApiResult<T>>(config)
  return res.data
}

export default request
