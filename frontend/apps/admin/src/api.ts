export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
  }
}
let csrf: { headerName: string; token: string } | undefined
export async function refreshCsrf() {
  csrf = await request<{ headerName: string; token: string }>('/api/auth/csrf')
}
export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers)
  if (!['GET', 'HEAD'].includes(options.method || 'GET')) {
    if (!csrf) await refreshCsrf()
    headers.set(csrf!.headerName, csrf!.token)
    if (typeof options.body === 'string' && !headers.has('Content-Type'))
      headers.set('Content-Type', 'application/json')
  }
  let response: Response
  try {
    response = await fetch(path, {
      ...options,
      headers,
      credentials: 'same-origin',
      signal: options.signal || AbortSignal.timeout(20000),
    })
  } catch {
    throw new ApiError(
      0,
      '连接失败或请求超时。写入结果可能已生效，请刷新确认后再操作。',
    )
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    if (response.status === 401 && path !== '/api/auth/login') {
      csrf = undefined
      window.dispatchEvent(new Event('session-expired'))
    }
    const fields = problem.fieldErrors
      ?.map((item: { message: string }) => item.message)
      .join('；')
    const messages: Record<number, string> = {
      401: '账号或密码错误，或登录已过期',
      403: '无操作权限或安全令牌已失效，请重新登录',
      409: '数据已变化，请刷新后重试',
    }
    throw new ApiError(
      response.status,
      fields ||
        problem.detail ||
        messages[response.status] ||
        '操作失败，请稍后重试',
    )
  }
  return response.status === 204 ? (undefined as T) : response.json()
}
export interface Session {
  username: string
  authorities: string[]
}
export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export interface Warehouse {
  id: number
  code: string
  name: string
  purpose: string
  form: string
  managementCategory: string
  status: string
  remark: string | null
  version: number
  createdBy: string
  createdAt: string
  updatedBy: string
  updatedAt: string
}
export const purposes: Record<string, string> = {
  RAW_MATERIAL: '原材料',
  SEMI_FINISHED: '半成品',
  FINISHED_GOODS: '成品',
  LINE_SIDE: '线边',
  MRO: '维修备件',
}
export const forms: Record<string, string> = {
  PHYSICAL: '实体仓库',
  LOGICAL: '逻辑仓库',
}
export const categories: Record<string, string> = {
  GENERAL: '普通',
  HAZARDOUS_CHEMICAL: '危险化学品',
}
export const statuses: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}
