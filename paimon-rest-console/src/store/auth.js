import { reactive } from 'vue'

import { ApiError } from '@/api/client.js'
import { consoleAuthApi } from '@/api/console-auth.js'
import { credentials } from '@/store/credentials.js'

/**
 * 登录态。
 *
 * <p><b>控制台不判断认证方式，只照做。</b>支持哪些登录方式、令牌端点在哪儿、
 * OIDC 的授权端点是什么，全部由服务端的 {@code GET /api/console/v1/auth} 下发。
 * 这不是偷懒：控制台是构建期打包的静态资源，读不到服务端配置；
 * OIDC 的端点更是只有服务端能发现（要拉 {@code .well-known/openid-configuration}）。
 * 前端硬编码一份必然漂移，而漂移的表现是「点了登录按钮没反应」。
 *
 * <p><b>令牌只有一处存储</b>（`localStorage` 的 `paimon.console.token`，
 * 见 {@link credentials}）。账号密码换来的、客户端凭据换来的、SSO 换来的、
 * 手填的静态令牌，在这里都是同一个字符串。因此「登录」与「在设置页手填令牌」不是两套机制，
 * 而是同一件事的两个入口——API 客户端不需要知道令牌是怎么来的。
 *
 * <p><b>服务端没有会话。</b>令牌是自包含的 JWT，服务端不存任何东西，
 * 因此这里也没有「服务端记住的登录状态」可查——{@code session} 字段回答的是
 * 「你递过来的这个令牌算不算数」，而不是「你之前登录过没有」。
 * 直接的后果是 <b>登出只是忘掉本机令牌</b>：令牌在有效期内仍然有效，
 * 要真正作废只能轮换签名密钥（那会作废所有人的令牌）。这是自包含令牌的固有代价，
 * 换取的是多实例互认、重启不掉线。
 */

/** 认证方式取值，与服务端 `ConsoleDtos.AuthMethod` 一一对应。 */
export const AUTH_METHOD = {
  PASSWORD: 'password',
  CLIENT_CREDENTIALS: 'client-credentials',
  OIDC: 'oidc',
  STATIC_TOKEN: 'static-token',
}

/**
 * 令牌来源的可读名，与服务端 `TokenAuthenticationService.SOURCE_*` 对应。
 *
 * <p>未知取值原样显示而不是吞掉：新增一种来源时，控制台不该把登录者
 * 显示成「未登录」——那会让人以为是登录失败。
 */
const SOURCE_LABELS = {
  'static-token': '配置里的静态令牌',
  'console-access-token': '控制台签发',
  oidc: '外部身份提供方（OIDC）',
}

const state = reactive({
  /** 服务端是否要求令牌（`paimon.rest.auth.enabled`），也就是数据面是否受保护。 */
  authEnabled: false,
  /**
   * 控制台自身是否要求先登录（`paimon.rest.auth.console.required`，默认 true）。
   *
   * <p>与 `authEnabled` 分开：两者同时为 true 时是一回事，但只有这个为 true 时
   * 「登录」只挡住界面——`/v1/**` 与管理 API 仍然匿名可调。界面必须如实说出这一点，
   * 否则会给人「本服务端已受保护」的错觉。
   */
  consoleRequired: false,
  /** 服务端提供的登录方式，取值见 {@link AUTH_METHOD}；顺序即建议的展示顺序。 */
  methods: [],
  /** 客户端凭据流程的令牌端点（绝对路径）。 */
  tokenEndpoint: '',
  /** OIDC 公开客户端参数；不可用时为 `null`。 */
  oidc: null,
  /** 当前令牌是否被服务端认可。 */
  authenticated: false,
  /** 令牌对应的主体名；未认证时为空串。 */
  principal: '',
  /** 令牌来源；未认证时为空串。 */
  source: '',
  /** 令牌来源的可读名。 */
  sourceLabel: '',
  /** 令牌失效时刻（毫秒）；静态令牌没有失效时刻，为 `null`。 */
  expiresAtMillis: null,
  /** 该主体的凭据是否被标记为待轮换，供界面提醒。 */
  credentialRotationRequired: false,
  /** 是否已经成功问过服务端一次。避免每次路由跳转都打一次 /auth。 */
  checked: false,
  /** 正在询问服务端。守卫据此避免并发重复请求。 */
  checking: false,
  /** 询问服务端失败的原因（服务端没起来、路径写错等）。 */
  error: '',
})

/**
 * 控制台是否必须先登录才能进入。
 *
 * <p>判据与服务端的 `RestServerProperties.Auth.Console#loginRequired` 是同一个式子：
 * 服务端整体要求令牌，或者控制台自己要求登录。**写成一处**而不是两个条件在视图里各写一遍，
 * 是因为两处只要有一处写漏，症状就是「守卫放行了、页面却全是 401」，很难一眼看出。
 */
function loginRequired() {
  return state.authEnabled || state.consoleRequired
}

/**
 * 门禁是否只挡住界面。
 *
 * <p>即「控制台要求登录，但数据面仍然开放」。为 true 时界面必须写明
 * `curl /v1/**` 依然能拿到数据——这层登录不是安全边界。
 */
function gateOnly() {
  return state.consoleRequired && !state.authEnabled
}

/** 是否提供某种登录方式。 */
function supports(method) {
  return state.methods.includes(method)
}

function apply(payload) {
  state.authEnabled = Boolean(payload?.authEnabled)
  state.consoleRequired = Boolean(payload?.consoleRequired)
  state.methods = Array.isArray(payload?.methods) ? payload.methods : []
  state.tokenEndpoint = payload?.tokenEndpoint || ''
  state.oidc = payload?.oidc || null

  const session = payload?.session || {}
  state.authenticated = Boolean(session.authenticated)
  state.principal = session.principal || ''
  state.source = session.source || ''
  state.sourceLabel = SOURCE_LABELS[session.source] || session.source || ''
  state.expiresAtMillis = session.expiresAtMillis || null
  state.credentialRotationRequired = Boolean(session.credentialRotationRequired)

  state.checked = true
  state.error = ''
}

function resetSession() {
  state.authenticated = false
  state.principal = ''
  state.source = ''
  state.sourceLabel = ''
  state.expiresAtMillis = null
  state.credentialRotationRequired = false
}

/**
 * 向服务端确认当前令牌。
 *
 * <p>失败时**不**改变 `checked`，让下一次导航重试：把一次网络抖动
 * 当成「已确认」会让整个会话停留在错误的判断上。
 */
async function refresh() {
  state.checking = true
  try {
    apply(await consoleAuthApi.auth())
    return true
  } catch (error) {
    state.error = error?.message || String(error)
    return false
  } finally {
    state.checking = false
  }
}

/**
 * 首次导航时确认一次；已确认过则直接用缓存结果。
 *
 * @param {boolean} force 跳过缓存重新问一次。OIDC 回调页要用它：
 *   那里必须拿到**当前**的 `oidc` 配置（授权端点是刚发现出来的），
 *   而缓存可能来自跳去 IdP 之前的那一次，中间服务端配置可能已经变了。
 */
async function ensureChecked(force = false) {
  if (state.checked && !force) {
    return true
  }
  return refresh()
}

/**
 * 令牌是否已过期。
 *
 * <p>只看令牌里写的 `exp`，不问服务端：过期判定不需要网络，而每次导航都问一次
 * 会让守卫多一次往返。静态令牌没有 `exp`（运维删掉它才算失效），因此永远返回 false。
 */
function expired() {
  return Boolean(state.expiresAtMillis) && Date.now() >= state.expiresAtMillis
}

/** 令牌剩余有效秒数；静态令牌或未认证时为 `null`。 */
function expiresInSeconds() {
  if (!state.expiresAtMillis) return null
  return Math.max(0, Math.round((state.expiresAtMillis - Date.now()) / 1000))
}

/**
 * 清掉一个已经过期的令牌。
 *
 * <p>由路由守卫在跳转前调用。留着它没有好处：请求会一路 401，
 * 而错误提示是「未通过鉴权」，看起来像是权限不够而不是需要重新登录。
 */
function dropExpiredToken() {
  if (!state.authenticated || !expired()) {
    return false
  }
  credentials.saveToken('')
  resetSession()
  return true
}

/**
 * 收下一个令牌：存下来，然后问问服务端认不认。
 *
 * <p>三条登录路径共用这里。**认不认由服务端说了算**，不在前端解析 JWT——
 * 前端解出来的 `exp` 只能骗自己，签名、受众、签发者都验不了。
 *
 * <p><b>没通过就还原成上一份令牌。</b>「先落盘再校验」是必须的（问服务端时
 * 带的正是这个令牌），但校验失败时必须回滚，否则：
 *
 * <ul>
 *   <li>一个服务端明确拒绝的令牌被留在本地，之后每个请求都带着它，
 *       而错误提示是「未通过鉴权」，看起来像权限不足；
 *   <li>更糟的是，在已登录状态下尝试换一个令牌（SSO 回调、改错令牌）时，
 *       一次失败会把**正在用的、仍然有效的**令牌一起清掉——用户会莫名其妙掉线。
 * </ul>
 *
 * @returns {Promise<{ok: boolean, error: string}>}
 */
async function adopt(token) {
  const previous = credentials.token
  credentials.saveToken(token)
  state.checked = false

  if (!(await refresh())) {
    // 服务端都问不到，就谈不上「认不认这个令牌」：还原，把失败原因交给调用方
    credentials.saveToken(previous)
    state.checked = false
    return { ok: false, error: state.error }
  }
  if (state.authenticated) {
    return { ok: true, error: '' }
  }

  const rejected = '服务端不认可这个令牌（可能已过期、签发者或受众不匹配，或令牌不属于任何已知主体）'
  credentials.saveToken(previous)
  state.checked = false
  // 重新确认一次：还原之后「当前是谁」要跟着回到上一份令牌的状态，
  // 不能停留在「用坏令牌问出来的答案」上
  await refresh()
  return { ok: false, error: rejected }
}

/**
 * 用主体凭据换令牌（OAuth 2.0 客户端凭据流程）。
 *
 * <p>返回 `{ok, error}` 而不是抛异常：调用方是表单，它要做的是把错误显示在
 * 输入框旁边，而不是走统一的错误提示（那种提示会被误读成「请求失败」，
 * 掩盖掉「密钥不对」这个真正的原因——两者对用户要做的事完全不同）。
 */
async function loginWithClientCredentials(clientId, clientSecret, scope) {
  let issued
  try {
    issued = await consoleAuthApi.token(clientId, clientSecret, scope)
  } catch (error) {
    return { ok: false, error: error instanceof ApiError ? error.message : String(error?.message || error) }
  }
  return adopt(issued.access_token)
}

/**
 * 用用户名 + 密码换令牌（控制台的默认方式）。
 *
 * <p>与 {@link loginWithClientCredentials} 走同一条收尾路径（{@link adopt}）：这里只负责
 * 「换到一个令牌字符串」，之后「服务端认不认」由同一处判定。三条登录路径共用收尾，
 * 因此「换令牌失败」与「令牌不被认可」的提示措辞不会三种各说一套。
 *
 * <p>返回 `{ok, error}` 而不是抛异常，理由同客户端凭据那条：表单要把失败原因
 * 显示在输入框旁边，而不是走统一的错误提示。
 *
 * <p><b>失败时清掉密码框</b>由调用方做（这里不碰表单状态）：本模块不认识 DOM。
 * 服务端的 401 文案对「用户名不存在」与「密码不对」一字不差，
 * 因此这里也不该给出任何「用户名可能写错了」的暗示——那是服务端刻意抹掉的差别。
 */
async function loginWithPassword(username, password) {
  let issued
  try {
    issued = await consoleAuthApi.passwordLogin(username, password)
  } catch (error) {
    return { ok: false, error: error instanceof ApiError ? error.message : String(error?.message || error) }
  }
  return adopt(issued.accessToken)
}

/**
 * 收下静态令牌。
 *
 * <p>与上面那条的差别只在来源，语义完全一样：交给 {@link adopt} 校验，
 * 服务端不认就不留。**不保留「服务端不认但先存着」的令牌**——
 * 一个不会被校验的令牌留在本机没有任何用处，只会让人以为已经配好了。
 * 需要把任意令牌写进本机（例如为将来打开鉴权先备好），走「连接设置」页，
 * 那里的语义就是明确的「保存这个字符串」。
 */
function loginWithStaticToken(token) {
  return adopt(token)
}

/**
 * 登出。
 *
 * <p><b>不通知服务端，因为服务端不知道这件事。</b>令牌是自包含的 JWT，
 * 服务端没有会话表可以删；这个请求发出去也无处可发（上一版有
 * {@code /api/console/v1/logout}，它删的是内存里的会话，而现在没有那份内存了）。
 * 于是登出的语义就是「忘掉本机这份令牌」——它仍然在有效期内有效，
 * 这一点必须如实写出来，否则会给人「已经踢下线了」的错觉。
 */
function logout() {
  credentials.saveToken('')
  resetSession()
  state.checked = false
  return refresh()
}

export const auth = {
  state,
  AUTH_METHOD,
  loginRequired,
  gateOnly,
  supports,
  expired,
  expiresInSeconds,
  dropExpiredToken,
  refresh,
  ensureChecked,
  adopt,
  loginWithPassword,
  loginWithClientCredentials,
  loginWithStaticToken,
  logout,
}
