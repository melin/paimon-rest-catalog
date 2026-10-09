import { get, post, request } from '@/api/client.js'

/**
 * 控制台认证：登录引导与令牌签发。
 *
 * <p>与 {@link managementApi} 分开，是因为这两条调用有一个别处没有的性质：
 * **它们必须在没有令牌时也能成功**（服务端对它们不鉴权）。
 * 混进管理资源那一堆里，「哪些调用需要令牌」这个关键区别就会被淹没。
 *
 * <p>这里只做「怎么拿到令牌」，不记得任何状态——令牌存哪儿、当前是谁，
 * 是 {@link auth} 的事。分成两层是为了让「换一种登录方式」只改这个文件。
 */
export const consoleAuthApi = {
  /**
   * 登录引导：服务端要不要令牌、支持哪些认证方式、令牌端点在哪儿、
   * 以及请求头里那个令牌算不算数。
   *
   * <p>它不需要令牌，但**带上**也不需要额外处理：服务端会顺便回答「你是谁」，
   * 于是刷新页面时不用多打一次请求就能确认手上的令牌还有效。
   */
  auth: () => get('auth'),

  /**
   * 用户名 + 密码换令牌。
   *
   * <p>发 JSON 而不是表单：这个端点是控制台自己的扩展端点，调用方只有控制台，
   * 不背 OAuth 那套 `application/x-www-form-urlencoded` 的约定（见
   * `ConsoleDtos.PasswordLoginRequest` 的说明）。请求形状与下面的 `token` 不同，
   * 因此**不能**合并成一条：把两者混起来会让人以为账号密码能拿去换 OAuth 令牌。
   *
   * <p>401 的报文对「用户名不存在」与「密码不对」是一字不差的
   * `invalid username or password`，因此这里不做任何「是不是用户名写错了」的判断——
   * 前端区分它们就等于把服务端刻意抹掉的差别又露出来了。
   *
   * @param {string} username 服务端 `paimon.rest.auth.console.password.users` 里的用户名
   * @param {string} password 明文密码
   */
  passwordLogin: (username, password) => post('passwordLogin', { body: { username, password } }),

  /**
   * OAuth 2.0 客户端凭据流程换令牌。
   *
   * <p>按 RFC 6749 发 `application/x-www-form-urlencoded`，不是 JSON：
   * 这个端点的调用方不只有本控制台，用 `curl -d` 或任何 OAuth 库发出来的
   * 都是表单。前端跟着规范走，就不会出现「只有控制台能调通」的端点。
   *
   * @param {string} clientId     主体名（`paimon_principal.client_id`）
   * @param {string} clientSecret 主体密钥
   * @param {string} [scope]      留空表示服务端默认的 `PRINCIPAL_ROLE:ALL`
   */
  token: (clientId, clientSecret, scope) => request('token', {
    form: {
      grant_type: 'client_credentials',
      client_id: clientId,
      client_secret: clientSecret,
      scope: scope || undefined,
    },
  }),
}
