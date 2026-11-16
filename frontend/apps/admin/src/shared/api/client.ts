import type { components } from './schema'

export interface FieldError {
  field: string
  message: string
}

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: string
  fieldErrors?: FieldError[]
}

export type Session = Required<components['schemas']['SessionResponse']>
export type CsrfToken = Required<components['schemas']['CsrfResponse']>
export type Page<T> = Omit<Required<components['schemas']['Page']>, 'items'> & {
  items: T[]
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
  }
}

let csrf: CsrfToken | undefined

export async function refreshCsrf() {
  csrf = await request<CsrfToken>('/api/auth/csrf')
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
      ['GET', 'HEAD'].includes(options.method || 'GET')
        ? '读取失败或请求超时，请重新查询。'
        : '连接失败或请求超时。写入结果可能已生效，请刷新确认后再操作。',
    )
  }
  if (!response.ok) {
    const problem: ProblemDetail = await response.json().catch(() => ({}))
    if (response.status === 401 && path !== '/api/auth/login') {
      csrf = undefined
      window.dispatchEvent(new Event('session-expired'))
    }
    const fields = problem.fieldErrors
      ?.map((item: FieldError) => item.message)
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
