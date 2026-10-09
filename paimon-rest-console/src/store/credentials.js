import { reactive } from 'vue'

import { getBaseUrl, getToken, setBaseUrl, setToken } from '@/api/client.js'

/**
 * 连接凭证（访问令牌与 API 基址）。
 *
 * <p>做成响应式对象而不是每次直接读 `localStorage`：
 * 顶栏要显示「是否已配置令牌」，而 `localStorage` 的读取不参与 Vue 的依赖收集，
 * 在设置页保存后顶栏不会跟着变。
 */
export const credentials = reactive({
  token: getToken(),
  baseUrl: getBaseUrl(),

  save(token, baseUrl) {
    setToken(token)
    setBaseUrl(baseUrl)
    this.token = getToken()
    this.baseUrl = getBaseUrl()
  },

  /**
   * 只写令牌，不动基址。
   *
   * <p>登录路径用它而不是 `save(token, baseUrl)`：登录时基址是**另一个**输入框
   * 的当前值，把它一起写回去等于让登录顺带提交一次连接设置——
   * 而登录者可能压根没打开过那一页。
   */
  saveToken(token) {
    setToken(token)
    this.token = getToken()
  },

  clear() {
    setToken('')
    setBaseUrl('')
    this.token = ''
    this.baseUrl = ''
  },

  get configured() {
    return Boolean(this.token)
  },
})
