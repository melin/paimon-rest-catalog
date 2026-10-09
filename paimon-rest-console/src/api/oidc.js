/**
 * OpenID Connect 授权码 + PKCE（浏览器侧）。
 *
 * <p>对应服务端 {@code paimon.rest.auth.console.oidc.*}。本文件做的是**换令牌**：
 * 把用户送到 IdP，接回授权码，再用授权码换回一个令牌。它不验证令牌——
 * 验证是服务端的事（{@code OidcService} 拉 JWKS 验签），
 * 前端验了也不算数，而且验不了（受众、签发者、时钟，任何一项都可能与服务端理解不同）。
 *
 * <p><b>为什么是 PKCE 而不是授权码 + 客户端密钥。</b>控制台是纯静态资源，
 * 没有服务端可以保管密钥——任何放进前端构建产物的密钥都等同于公开。
 * PKCE 用一次性的 {@code code_verifier} 代替密钥：它只存在于这次登录的浏览器里，
 * 即使授权码被截获，没有 verifier 也换不到令牌。
 *
 * <p><b>端点全部由服务端下发</b>（{@code /api/console/v1/auth} 的 `oidc` 字段）：
 * 发现文档只有服务端能拉，前端硬编码一份必然漂移。
 *
 * <p><b>为什么用 sessionStorage 存 verifier 而不是 localStorage。</b>
 * 它只在「跳出去 → 跳回来」这一次往返里有效，用完即弃。
 * 放进 localStorage 会让一个不再需要的秘密长期留在磁盘上，还会串到别的标签页
 * （两个标签页同时登录时互相覆盖 verifier）。
 */

const VERIFIER_KEY = 'paimon.console.oidc.verifier'
const STATE_KEY = 'paimon.console.oidc.state'
const RETURN_TO_KEY = 'paimon.console.oidc.returnTo'

/** 控制台的回调路径。与服务端 `oidc.redirect-uri` 的示例一致。 */
const CALLBACK_PATH = '/console/auth/callback'

/**
 * 回跳地址。
 *
 * <p>优先用服务端配置的那个：它必须在 IdP 侧登记过，IdP 会逐字比对，
 * 前端自己拼一个（哪怕看起来一样）在代理、自定义端口、多域名下很容易差一点点，
 * 而报错是 IdP 页面上一句语焉不详的 `invalid_redirect_uri`。
 * 服务端没配时才退回按当前位置推出来的值。
 */
export function resolveRedirectUri(oidc) {
  const configured = (oidc?.redirectUri || '').trim()
  if (configured) {
    return configured
  }
  return `${window.location.origin}${CALLBACK_PATH}`
}

/**
 * 发起授权：返回要跳转的 URL，并把 PKCE 的中间状态留在本标签页。
 *
 * @param {object} oidc     `/api/console/v1/auth` 下发的 `oidc` 字段
 * @param {string} returnTo 登录成功后要回到的站内路径
 */
export async function beginAuthorization(oidc, returnTo = '') {
  if (!oidc?.authorizationEndpoint || !oidc?.clientId) {
    throw new Error('服务端没有下发完整的 OIDC 配置，无法发起登录')
  }
  const verifier = randomBase64Url(32)
  const stateValue = randomBase64Url(16)
  sessionStorage.setItem(VERIFIER_KEY, verifier)
  sessionStorage.setItem(STATE_KEY, stateValue)
  sessionStorage.setItem(RETURN_TO_KEY, returnTo || '')

  const url = new URL(oidc.authorizationEndpoint)
  const parameters = {
    response_type: 'code',
    client_id: oidc.clientId,
    redirect_uri: resolveRedirectUri(oidc),
    scope: oidc.scope || 'openid profile email',
    state: stateValue,
    code_challenge: await codeChallenge(verifier),
    code_challenge_method: 'S256',
  }
  for (const [key, value] of Object.entries(parameters)) {
    url.searchParams.set(key, value)
  }
  return url.toString()
}

/**
 * 完成授权：校验 `state`，用授权码换令牌。
 *
 * @returns {Promise<{token: string, expiresInSeconds: number|null, returnTo: string}>}
 */
export async function completeAuthorization(oidc, { code, state: returnedState }) {
  const verifier = sessionStorage.getItem(VERIFIER_KEY)
  const expectedState = sessionStorage.getItem(STATE_KEY)
  const returnTo = sessionStorage.getItem(RETURN_TO_KEY) || ''
  clearPending()

  if (!verifier) {
    // 直连回调地址、刷新回调页、换了标签页都会走到这里。
    // 不当作错误原因不明处理：真实原因就是「这次登录不是从本标签页发起的」
    throw new Error('这次登录的回调没有对应的本地记录，请重新从登录页发起')
  }
  // state 是防「别人构造一个回调把我登成他的账号」的唯一手段（CSRF）。
  // 不校验的话，攻击者只要诱使受害者打开一个带自己 code 的回调链接，
  // 受害者就会在不知情的情况下以攻击者的身份操作
  if (!expectedState || returnedState !== expectedState) {
    throw new Error('state 不匹配：这次回调不是本标签页发起的登录，已拒绝')
  }

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: resolveRedirectUri(oidc),
    client_id: oidc.clientId,
    code_verifier: verifier,
  })

  let response
  try {
    response = await fetch(oidc.tokenEndpoint, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        // 公开客户端：不带 Authorization。带上 Basic 反而会被部分 IdP 拒绝
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
      },
      body: body.toString(),
    })
  } catch (cause) {
    throw new Error(`无法连接身份提供方（${oidc.tokenEndpoint}）：${cause.message}`)
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
    const detail = payload?.error_description || payload?.error || `HTTP ${response.status}`
    throw new Error(`换取令牌失败：${detail}`)
  }

  // 优先 id_token：OIDC 规定它一定是 IdP 签名的 JWT，且 `aud` 就是 client_id，
  // 服务端要的 iss / aud / exp / sub 全都在。access_token 不保证是 JWT
  // （不少 IdP 发的是不透明串），受众也常是另一个资源标识
  const token = payload?.id_token || payload?.access_token
  if (!token) {
    throw new Error('身份提供方既没有返回 id_token 也没有 access_token')
  }
  return {
    token,
    expiresInSeconds: Number.isFinite(payload?.expires_in) ? payload.expires_in : null,
    returnTo,
  }
}

/** 丢弃本次登录的中间状态。成功、失败、放弃都要调，避免下次误用。 */
export function clearPending() {
  sessionStorage.removeItem(VERIFIER_KEY)
  sessionStorage.removeItem(STATE_KEY)
  sessionStorage.removeItem(RETURN_TO_KEY)
}

/** S256 挑战 = base64url(SHA-256(verifier))。 */
async function codeChallenge(verifier) {
  if (!window.crypto?.subtle) {
    // 只有安全上下文（https 或 localhost）才有 SubtleCrypto。
    // 用明文 http 访问远端部署时会走到这里，而 IdP 通常也要求 https，因此直接说清楚
    throw new Error('当前页面不是安全上下文（需 https 或 localhost），无法使用 PKCE 的 S256 挑战')
  }
  const digest = await window.crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

/** PKCE 要求的高熵随机串（每次登录一份，用完即弃）。 */
function randomBase64Url(bytes) {
  const buffer = new Uint8Array(bytes)
  window.crypto.getRandomValues(buffer)
  return base64Url(buffer)
}

/** base64url 无填充，RFC 7636 第 4 节与应用声明（JWT）都用这个字母表。 */
function base64Url(bytes) {
  let binary = ''
  for (const byte of bytes) {
    binary += String.fromCharCode(byte)
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
