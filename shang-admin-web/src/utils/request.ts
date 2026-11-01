import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

export interface ApiResult<T = unknown> {
  code: number
  msg: string
  data: T
  traceId?: string
}

const TOKEN_KEY = 'shang_admin_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

const request: AxiosInstance = axios.create({
  baseURL: '/',
  timeout: 30000,
})

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // 后端分页参数为 size，前端统一传 pageSize 时自动映射
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
      ElMessage.error(payload.msg || '请求失败')
      return Promise.reject(payload)
    }
    return response
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      clearToken()
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = `/login?redirect=${redirect}`
      }
      return Promise.reject(error)
    }
    const msg =
      error.response?.data?.msg || error.message || '网络异常，请稍后重试'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export async function http<T = unknown>(config: AxiosRequestConfig): Promise<ApiResult<T>> {
  const res = await request.request<ApiResult<T>>(config)
  return res.data
}

export default request
