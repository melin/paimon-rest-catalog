/**
 * 前端登录逻辑的实地校验。
 *
 * <p>把 `src/store/auth.js` 与 `src/api/*` **原样**（经 vite 打包，别名解析与正式构建一致）
 * 跑在 Node 里，对着一个**运行中的服务端**完成真实的登录。
 *
 * <p>为什么值得单独做一次：HTTP 层的验收（`scripts/sweep-console-auth.sh`）证明的是
 * 服务端的行为，证明不了**前端发出的请求长什么样**。字段名写成 `clientId` 而不是
 * `client_id`、请求体编成 JSON 而不是表单、令牌写到别的 key、登录后没把令牌挂到
 * 请求头上、校验失败没回滚——这些在服务端测试里全绿，表现却是「登录按钮点了没反应」
 * 或「一次写错令牌就把正在用的会话弄丢了」。
 *
 * <p>用法（服务端需以 `paimon.rest.auth.enabled=true` 运行）：
 *
 * <pre>
 * BASE=http://127.0.0.1:8080 \
 * CLIENT_ID=&lt;引导主体 clientId&gt; CLIENT_SECRET=&lt;明文密钥&gt; \
 * STATIC_TOKEN=&lt;可选：登记过的静态令牌&gt; \
 * CONSOLE_USER=&lt;可选，默认 admin&gt; CONSOLE_PASSWORD=&lt;可选，默认 admin&gt; \
 *   npm run check:login
 * </pre>
 *
 * <p>引导主体的凭据在服务端启动日志里（搜 `created bootstrap principal`）。
 *
 * <p>**连续运行有次数上限**：第 2 节会故意输错一次密码，而服务端对
 * `console.password` 的失败限速默认是 5 次 / 分钟（按「来源 IP + 用户名」计数）。
 * 一分钟内跑超过 5 次会被 429 拒绝，那不是回归，是限速生效。
 *
 * <p>退出码 1 表示有不一致。
 */

const BASE = process.env.BASE || 'http://127.0.0.1:8080'
const PRINCIPAL = process.env.PRINCIPAL || 'root'
const STATIC_TOKEN = process.env.STATIC_TOKEN || ''

function required(name) {
  const value = process.env[name]
  if (!value) {
    console.error(`缺少环境变量 ${name}`)
    process.exit(2)
  }
  return value
}

const CLIENT_ID = required('CLIENT_ID')
const CLIENT_SECRET = required('CLIENT_SECRET')

// ------------------------------------------------------------------ 浏览器全局的替身
//
// 只补到够用：localStorage 是令牌唯一的落脚点（client.js 在模块初始化时就读它，
// 因此必须在 import 之前装好）；window.location.origin 是 OIDC 回跳地址的兜底。
const memory = new Map()
globalThis.localStorage = {
  getItem: (key) => (memory.has(key) ? memory.get(key) : null),
  setItem: (key, value) => memory.set(key, String(value)),
  removeItem: (key) => memory.delete(key),
  clear: () => memory.clear(),
}
globalThis.sessionStorage = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
}
globalThis.window = { location: { origin: BASE } }

// ------------------------------------------------------------------ 断言
let pass = 0
let fail = 0

function check(label, ok, detail = '') {
  if (ok) {
    pass += 1
    console.log(`ok [] ${label}${detail ? `（${detail}）` : ''}`)
  } else {
    fail += 1
    console.log(`!! [] ${label}${detail ? `（${detail}）` : ''}`)
  }
}

// ------------------------------------------------------------------ 记录前端发出的请求
const calls = []
const realFetch = globalThis.fetch
globalThis.fetch = async (url, init = {}) => {
  calls.push({
    url: String(url),
    method: init.method || 'GET',
    headers: init.headers || {},
    body: init.body,
  })
  return realFetch(url, init)
}

function lastCall(predicate) {
  return [...calls].reverse().find(predicate)
}

// 基址写成本机地址：client.js 默认拼相对路径（同源），Node 的 fetch 要求绝对 URL。
// 这顺带把「设置页填 baseUrl」这条路径也走了一遍。
localStorage.setItem('paimon.console.baseUrl', BASE)

const { auth } = await import('@/store/auth.js')

console.log('\n========== 1. 登录引导 ==========')
check('refresh 成功', await auth.refresh(), auth.state.error)
check('服务端要求令牌', auth.state.authEnabled === true, `authEnabled=${auth.state.authEnabled}`)
check('控制台要求先登录', auth.state.consoleRequired === true,
  `consoleRequired=${auth.state.consoleRequired}`)
check('下发用户名密码方式', auth.supports('password'))
check('它排在第一位（不需要先建主体）', auth.state.methods[0] === 'password',
  auth.state.methods.join(', '))
check('下发客户端凭据方式', auth.supports('client-credentials'))
// 静态令牌的页签只在服务端登记过令牌时出现，而本脚本的 STATIC_TOKEN 与启动参数
// `paimon.rest.auth.tokens[0]` 是同一个值，因此它正好是这条规则的判据：
// 给了就该出现，没给就不该出现。两种都是被断言的行为，没有「跳过」这一档——
// 不显示一个填了也进不去的页签，正是这次改动的目的
if (STATIC_TOKEN) {
  check('登记过静态令牌，因此下发静态令牌方式', auth.supports('static-token'))
} else {
  check('没登记静态令牌，因此不下发该方式（登录页不显示这个页签）',
    !auth.supports('static-token'), auth.state.methods.join(', '))
}
check('匿名时未认证', auth.state.authenticated === false)
check('因此需要登录', auth.loginRequired() === true)
check('令牌端点用 Polaris 路径',
  auth.state.tokenEndpoint === '/api/catalog/v1/oauth/tokens', auth.state.tokenEndpoint)

console.log('\n========== 2. 用户名密码（控制台默认方式） ==========')
// 账号来自服务端配置 `paimon.rest.auth.console.password.users`，默认 admin/admin。
// 改过配置的部署用 CONSOLE_USER / CONSOLE_PASSWORD 覆盖。
const CONSOLE_USER = process.env.CONSOLE_USER || 'admin'
const CONSOLE_PASSWORD = process.env.CONSOLE_PASSWORD || 'admin'
// 没配 `password.principals` 映射时，主体名就是用户名
const CONSOLE_PRINCIPAL = process.env.CONSOLE_PRINCIPAL || CONSOLE_USER

calls.length = 0
const wrongPassword = await auth.loginWithPassword(CONSOLE_USER, 'definitely-not-the-password')
check('密码不对被拒', wrongPassword.ok === false)
check('报文与「用户名不存在」一字不差（服务端刻意不区分）',
  wrongPassword.ok === false && wrongPassword.error.includes('invalid username or password'),
  wrongPassword.error)
check('失败不把令牌留在本地', localStorage.getItem('paimon.console.token') === null)

const viaPassword = await auth.loginWithPassword(CONSOLE_USER, CONSOLE_PASSWORD)
check('用户名密码登录成功', viaPassword.ok === true, viaPassword.error)
check('主体名来自服务端', auth.state.principal === CONSOLE_PRINCIPAL, auth.state.principal)
check('签发的是同一种访问令牌', auth.state.source === 'console-access-token', auth.state.source)
check('令牌写入同一处 localStorage', Boolean(localStorage.getItem('paimon.console.token')))
check('剩余有效期为正', auth.expiresInSeconds() > 0, `${auth.expiresInSeconds()}s`)

const loginCall = lastCall((call) => call.url.endsWith('/api/console/v1/login'))
check('打到控制台登录端点', Boolean(loginCall), loginCall?.url)
check('用 POST', loginCall?.method === 'POST', loginCall?.method)
check('JSON 编码（不是表单）',
  String(loginCall?.headers['Content-Type']).startsWith('application/json'),
  loginCall?.headers['Content-Type'])
const loginBody = (() => {
  try {
    return JSON.parse(loginCall?.body || '{}')
  } catch {
    return {}
  }
})()
check('字段名是 username / password（本端点是控制台扩展端点，不是 OAuth 表单）',
  loginBody.username === CONSOLE_USER && loginBody.password === CONSOLE_PASSWORD,
  Object.keys(loginBody).join(', '))

await auth.logout()

console.log('\n========== 3. 错误凭据 ==========')
const bad = await auth.loginWithClientCredentials(CLIENT_ID, 'not-the-secret')
check('错误密钥被拒', bad.ok === false)
check('错误信息取自 RFC 6749 的 error_description',
  bad.error.includes('invalid_client'), bad.error)
check('失败不把令牌留在本地', localStorage.getItem('paimon.console.token') === null)
check('失败后仍是未登录', auth.state.authenticated === false)

console.log('\n========== 4. 前端实际发出的请求 ==========')
const tokenCall = lastCall((call) => call.url.endsWith('/api/catalog/v1/oauth/tokens'))
check('打到令牌端点', Boolean(tokenCall), tokenCall?.url)
check('用 POST', tokenCall?.method === 'POST', tokenCall?.method)
check('表单编码而不是 JSON',
  String(tokenCall?.headers['Content-Type']).startsWith('application/x-www-form-urlencoded'),
  tokenCall?.headers['Content-Type'])
const params = new URLSearchParams(tokenCall?.body || '')
check('grant_type=client_credentials', params.get('grant_type') === 'client_credentials', params.get('grant_type'))
check('client_id 字段名正确（下划线）', params.get('client_id') === CLIENT_ID)
check('client_secret 字段名正确（下划线）', params.get('client_secret') === 'not-the-secret')
check('不主动带 scope（空值不该编成 scope=）', params.get('scope') === null, `scope=${params.get('scope')}`)

console.log('\n========== 5. 正确凭据登录 ==========')
const ok = await auth.loginWithClientCredentials(CLIENT_ID, CLIENT_SECRET)
check('登录成功', ok.ok === true, ok.error)
check('已认证', auth.state.authenticated === true)
check('主体名来自服务端', auth.state.principal === PRINCIPAL, auth.state.principal)
check('令牌来源正确', auth.state.source === 'console-access-token', auth.state.source)
const saved = localStorage.getItem('paimon.console.token')
check('令牌写入 paimon.console.token', Boolean(saved))
check('存的是裸令牌（不是 JSON 串）', Boolean(saved) && !saved.startsWith('{'), `长度 ${saved?.length}`)
check('剩余有效期为正', auth.expiresInSeconds() > 0, `${auth.expiresInSeconds()}s`)
check('未过期', auth.expired() === false)

console.log('\n========== 6. 登录后的请求自动带令牌 ==========')
calls.length = 0
await auth.refresh()
const followup = calls[0]
check('业务请求带 Bearer', String(followup?.headers.Authorization).startsWith('Bearer '),
  String(followup?.headers.Authorization).slice(0, 16) + '…')
check('刷新后仍认证', auth.state.authenticated === true)
check('凭据待轮换标记被读出来',
  typeof auth.state.credentialRotationRequired === 'boolean',
  String(auth.state.credentialRotationRequired))

console.log('\n========== 7. 登出 ==========')
await auth.logout()
check('登出清掉本地令牌', localStorage.getItem('paimon.console.token') === null)
check('登出后未认证', auth.state.authenticated === false)
check('登出后主体名清空', auth.state.principal === '')
check('登出后仍需要登录', auth.loginRequired() === true)

console.log('\n========== 8. 静态令牌（降级入口） ==========')
if (STATIC_TOKEN) {
  const viaStatic = await auth.loginWithStaticToken(STATIC_TOKEN)
  check('静态令牌登录成功', viaStatic.ok === true, viaStatic.error)
  check('来源是静态令牌', auth.state.source === 'static-token', auth.state.source)
  check('静态令牌没有失效时刻', auth.state.expiresAtMillis === null)
  check('因此永不过期', auth.expired() === false)

  // 已登录时的一次失败尝试：不能把正在用的令牌弄丢
  const rejected = await auth.loginWithStaticToken('some-unregistered-token')
  check('未登记的令牌被拒', rejected.ok === false, rejected.error)
  check('被拒后回滚到上一份令牌',
    localStorage.getItem('paimon.console.token') === STATIC_TOKEN,
    `本地令牌=${String(localStorage.getItem('paimon.console.token')).slice(0, 20)}…`)
  check('被拒后仍是原来那个身份', auth.state.authenticated === true)
  check('被拒后来源仍是静态令牌', auth.state.source === 'static-token', auth.state.source)

  // 未登录时写错令牌（登录页上的常见情况）：不该留下任何残留
  await auth.logout()
  const rejectedFresh = await auth.loginWithStaticToken('some-unregistered-token')
  check('未登录时写错令牌同样被拒', rejectedFresh.ok === false, rejectedFresh.error)
  check('拒绝后本地不留令牌', localStorage.getItem('paimon.console.token') === null)
  check('拒绝后仍是未登录', auth.state.authenticated === false)
} else {
  console.log('(跳过：未提供 STATIC_TOKEN)')
}

console.log('')
if (fail > 0) {
  console.log(`校验未通过：${fail} / ${pass + fail} 项不一致`)
  process.exit(1)
}
console.log(`全部通过：${pass} 项`)
