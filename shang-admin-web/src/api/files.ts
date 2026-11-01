import { http, type ApiResult } from '@/utils/request'

export interface UploadResult {
  id?: number
  objectKey?: string
  url: string
}

/** 服务端转存 OSS，返回可访问 URL */
export function uploadFile(
  file: File,
  bizType = 'PRODUCT',
): Promise<ApiResult<UploadResult>> {
  const data = new FormData()
  data.append('file', file)
  data.append('bizType', bizType)
  return http({
    url: '/admin/files/upload',
    method: 'POST',
    data,
    // 勿手动设 Content-Type，交给浏览器带 boundary
  })
}
