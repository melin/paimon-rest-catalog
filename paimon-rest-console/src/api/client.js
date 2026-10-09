import { ENDPOINTS } from './endpoints.js'

/**
 * HTTP 客户端：统一鉴权、URL 拼装与错误处理。
 *
 * <p>三个约定，前端其余部分都建立在这上面：
 *
 * 1. **路径一律来自 {@link ENDPOINTS}**，不在视图里手写字符串。
 * 2. **凭证只从 `localStorage` 读**。服务端开启 `paimon.rest.auth.enabled` 后
 *    所有 `/v1/**` 与 `/api/**` 都要求 `Authorization: Bearer <token>`。
 *    令牌有三种来源——客户端凭据换来的、SSO 换来的、手填的静态令牌——
 *    但**在这里只有一种**：`localStorage` 里的那个字符串。
 *    因此加一种登录方式不需要动这个文件。
 * 3. **错误统一抛 {@link ApiError}**。服务端两类错误响应都归一化成同一个异常：
 *    错误体形如 `{message, resourceType, resourceName, code}`，
 *    非 JSON 的响应（例如网关的 HTML 错误页）则退化为状态行文本。
 */

const TOKEN_KEY = 'paimon.console.token'
const BASE_URL_KEY = 'paimon.console.baseUrl'

/** 服务端返回的错误响应，或本地网络故障。 */
export class ApiError extends Error {
  constructor(message, status, payload, endpoint) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
    this.endpoint = endpoint
  }

  /** 是否为权限类失败，用于提示语措辞。 */
  get isForbidden() {
    return this.status === 401 || this.status === 403
  }
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(value) {
  if (value) {
    localStorage.setItem(TOKEN_KEY, value)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

/**
 * API 基址。默认空串，即同源——生产构建后控制台与服务端同端口，
 * 不填就是对的；只有把控制台部署到别处时才需要覆盖。
 */
export function getBaseUrl() {
  return localStorage.getItem(BASE_URL_KEY) || ''
}

export function setBaseUrl(value) {
  const trimmed = (value || '').trim().replace(/\/+$/, '')
  if (trimmed) {
    localStorage.setItem(BASE_URL_KEY, trimmed)
  } else {
    localStorage.removeItem(BASE_URL_KEY)
  }
}

/** 把 `{名字}` 占位替换为编码后的路径段。缺失参数直接报错，不要拼出半个 URL。 */
export function buildPath(name, params = {}) {
  const endpoint = ENDPOINTS[name]
  if (!endpoint) {
    throw new Error(`unknown endpoint: ${name}`)
  }
  const path = endpoint.path.replace(/\{(\w+)\}/g, (match, key) => {
    const value = params[key]
    if (value === undefined || value === null || value === '') {
      throw new Error(`${name} 缺少路径参数 ${key}`)
    }
    return encodeURIComponent(value)
  })
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params.query || {})) {
    if (value === undefined || value === null || value === '') continue
    search.append(key, value)
  }
  const query = search.toString()
  return query ? `${path}?${query}` : path
}

/**
 * 发一次请求。
 *
 * <p>`body` 与 `form` 只会有一个生效：前者发 JSON，后者发
 * `application/x-www-form-urlencoded`。两种都要，是因为令牌端点
 * （`POST /api/catalog/v1/oauth/tokens`）按 RFC 6749 收表单，
 * 而其余端点收 JSON——为一条例外改掉全部调用方的形状不划算。
 *
 * @param {string} name    {@link ENDPOINTS} 中的端点名
 * @param {object} options `params`（含 `query`）、`body` 或 `form`
 * @returns {Promise<object>} 解析后的 JSON；204 等空响应返回 `{}`
 */
export async function request(name, { params = {}, body, form } = {}) {
  const endpoint = ENDPOINTS[name]
  if (!endpoint) {
    throw new Error(`unknown endpoint: ${name}`)
  }
  const url = `${getBaseUrl()}${buildPath(name, params)}`
  const headers = { Accept: 'application/json' }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const init = { method: endpoint.method, headers }
  if (form !== undefined) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8'
    init.body = encodeForm(form)
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }

  let response
  try {
    response = await fetch(url, init)
  } catch (cause) {
    throw new ApiError(`无法连接服务端：${cause.message}`, 0, null, name)
  }

  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = null
    }
  }

  if (!response.ok) {
    throw new ApiError(messageOf(payload, response.status), response.status, payload, name)
  }

  return payload ?? {}
}

/**
 * 拼表单体。值为 `undefined` / `null` 的键直接丢掉——
 * 令牌端点对「传了个空 scope」与「没传 scope」的处理不同（前者要报错），
 * 把空值编码成 `scope=` 会凭空造出前一种情况。
 */
function encodeForm(form) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(form)) {
    if (value === undefined || value === null) continue
    search.append(key, value)
  }
  return search.toString()
}

/**
 * 从错误响应里取一句话。
 *
 * <p>三类形状都要认：本工程其余端点的 `{message}`、令牌端点的 RFC 6749
 * `{error, error_description}`（它没有 `message` 字段，不认就会退化成
 * 「请求失败（HTTP 400）」，把「client_secret 不对」这条真正有用的信息丢掉）、
 * 以及网关返回的 HTML 错误页（既没有 `message` 也没有 `error_description`）。
 */
function messageOf(payload, status) {
  if (payload?.message) {
    return payload.message
  }
  if (payload?.error_description) {
    return payload.error ? `${payload.error}：${payload.error_description}` : payload.error_description
  }
  if (status === 401) {
    return '未通过鉴权：请在「连接设置」里填写访问令牌'
  }
  return `请求失败（HTTP ${status}）`
}

/** 语法糖：`get/put/post/del`。动词必须与 {@link ENDPOINTS} 中声明的一致。 */
function send(verb) {
  return (name, options = {}) => {
    const endpoint = ENDPOINTS[name]
    if (!endpoint) {
      throw new Error(`unknown endpoint: ${name}`)
    }
    if (endpoint.method !== verb) {
      throw new Error(`${name} 声明为 ${endpoint.method}，不能按 ${verb} 调用`)
    }
    return request(name, options)
  }
}

export const get = send('GET')
export const put = send('PUT')
export const post = send('POST')
export const del = send('DELETE')

/** 把服务端错误压成一行可展示的文本，并尽量带上资源定位信息。 */
export function describeError(error) {
  if (!(error instanceof ApiError)) {
    return error?.message || String(error)
  }
  const parts = [error.message]
  if (error.payload?.resourceType || error.payload?.resourceName) {
    parts.push(`（${[error.payload.resourceType, error.payload.resourceName].filter(Boolean).join(' / ')}）`)
  }
  if (error.status) {
    parts.push(`[HTTP ${error.status}]`)
  }
  return parts.join(' ')
}
