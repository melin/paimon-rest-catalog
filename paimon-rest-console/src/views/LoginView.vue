<template>
  <div class="pc-login">
    <div class="pc-login-theme">
      <el-tooltip :content="theme.state.mode === 'dark' ? '切换到浅色' : '切换到深色'" placement="bottom">
        <el-button text circle @click="theme.toggle()">
          <el-icon><component :is="theme.state.mode === 'dark' ? 'Sunny' : 'Moon'" /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <div class="pc-login-card">
      <div class="pc-login-brand">
        <div class="pc-login-mark">P</div>
        <div>
          <div class="pc-login-name">Paimon REST</div>
          <div class="pc-login-sub">管理控制台</div>
        </div>
      </div>

      <!-- 问不到服务端：这是登录页最常见的失败，先把原因摆出来 -->
      <el-alert v-if="mode === 'unreachable'" type="error" :closable="false" show-icon class="pc-login-alert">
        <template #title>无法读取服务端的登录要求</template>
        <div class="pc-login-alert-body">{{ auth.state.error }}</div>
      </el-alert>

      <template v-if="mode === 'checking'">
        <div class="pc-login-hint">正在读取服务端的登录要求…</div>
      </template>

      <template v-else-if="mode === 'unreachable'">
        <el-button class="pc-login-submit" size="large" :loading="auth.state.checking" @click="retry">
          重试
        </el-button>
        <p class="pc-login-foot">
          也可以先到「<RouterLink :to="{ name: 'settings' }">连接设置</RouterLink>」改 API 基址。
        </p>
      </template>

      <!-- 服务端既不校验令牌也不要求控制台登录：这里不是登录，只是告诉来访者为什么不需要登录 -->
      <template v-else-if="mode === 'not-required'">
        <el-alert type="info" :closable="false" show-icon class="pc-login-alert">
          <template #title>服务端未要求登录</template>
          <div>
            当前部署的 <code class="pc-mono">paimon.rest.auth.enabled=false</code>
            且 <code class="pc-mono">paimon.rest.auth.console.required=false</code>，
            服务端既不校验令牌也不要求控制台登录，任何人访问本地址都可以读写全部数据。
            若这是生产环境，请把 <code class="pc-mono">auth.enabled</code> 设为
            <code class="pc-mono">true</code>（数据面与控制台一起受保护），
            或至少把 <code class="pc-mono">console.required</code> 设为
            <code class="pc-mono">true</code>（只挡界面，见该配置项的说明）。
          </div>
        </el-alert>
        <el-button type="primary" class="pc-login-submit" size="large" @click="enterConsole">
          进入控制台
        </el-button>
      </template>

      <template v-else>
        <!-- 这三种方式以上都是「服务端要求令牌」，只有一种例外：门禁开着而整体鉴权关着。
             那句话必须出现在登录页上——少了它，「要求登录」会被读成
             「本服务端已受保护」，而 curl 一个数据接口就能证明不是 -->
        <el-alert v-if="auth.gateOnly()" type="info" :closable="false" show-icon class="pc-login-alert">
          <template #title>这层登录只挡住界面</template>
          <div>
            服务端开启了 <code class="pc-mono">paimon.rest.auth.console.required</code>，
            而 <code class="pc-mono">paimon.rest.auth.enabled=false</code>：
            必须登录才能打开本控制台，但 <code class="pc-mono">/v1/**</code>
            与管理 API 仍然匿名可调。<strong>它不是安全边界</strong>——
            要保护数据，请把 <code class="pc-mono">auth.enabled</code> 设为
            <code class="pc-mono">true</code>。
          </div>
        </el-alert>

        <!-- 登录方式由服务端下发，页签按服务端给的顺序排。只有一种时不渲染页签头 -->
        <el-tabs v-if="tabs.length > 1" v-model="activeTab" class="pc-login-tabs">
          <el-tab-pane
            v-for="tab in tabs"
            :key="tab.name"
            :name="tab.name"
            :label="tab.label"
          />
        </el-tabs>

        <div v-else-if="tabs.length === 1" class="pc-login-single">
          {{ tabs[0].label }}
        </div>

        <el-alert
          v-if="auth.state.credentialRotationRequired"
          type="warning"
          :closable="false"
          show-icon
          class="pc-login-alert"
        >
          <template #title>这个主体的凭据待轮换</template>
          <div>登录成功，但服务端把该主体标记为凭据待轮换。请在主体管理页轮换后更新本地配置。</div>
        </el-alert>

        <!-- 方式一：用户名 + 密码（服务端配置里的账号，默认 admin/admin） -->
        <template v-if="activeTab === 'password'">
          <el-form label-position="top" class="pc-login-form" @submit.prevent>
            <el-form-item label="用户名">
              <el-input
                v-model="form.username"
                size="large"
                autocomplete="username"
                placeholder="服务端 paimon.rest.auth.console.password.users 里的账号"
                :prefix-icon="User"
                @keyup.enter="submitPassword"
              />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="form.password"
                size="large"
                type="password"
                show-password
                autocomplete="current-password"
                :prefix-icon="Lock"
                @keyup.enter="submitPassword"
              />
            </el-form-item>
          </el-form>

          <el-alert v-if="error" type="error" :closable="false" show-icon class="pc-login-alert">
            <template #title>登录失败</template>
            <div class="pc-login-alert-body">{{ error }}</div>
          </el-alert>

          <el-button
            type="primary"
            class="pc-login-submit"
            size="large"
            :loading="submitting"
            @click="submitPassword"
          >
            登录
          </el-button>

          <p class="pc-login-foot">
            账号来自服务端配置（<code class="pc-mono">paimon.rest.auth.console.password.users</code>，
            默认 <code class="pc-mono">admin/admin</code>），不是主体表里的凭据——
            它只回答「谁能打开控制台」，进来之后能做什么仍由主体角色授权决定。<br />
            服务端对所有失败一律回 <code class="pc-mono">invalid username or password</code>，
            不区分用户名与密码，这是刻意的（防止枚举用户名）。<br />
            登录后令牌保存在本机浏览器，{{ ttlText }}后失效。
          </p>
        </template>

        <!-- 方式二：OAuth 2.0 客户端凭据 -->
        <template v-else-if="activeTab === 'client-credentials'">
          <el-form label-position="top" class="pc-login-form" @submit.prevent>
            <el-form-item label="主体标识（client_id）">
              <el-input
                v-model="form.clientId"
                size="large"
                autocomplete="username"
                placeholder="paimon_principal.client_id"
                :prefix-icon="User"
                @keyup.enter="submitCredentials"
              />
            </el-form-item>
            <el-form-item label="主体密钥（client_secret）">
              <el-input
                v-model="form.clientSecret"
                size="large"
                type="password"
                show-password
                autocomplete="current-password"
                placeholder="创建主体时下发的密钥"
                :prefix-icon="Lock"
                @keyup.enter="submitCredentials"
              />
            </el-form-item>
          </el-form>

          <el-alert v-if="error" type="error" :closable="false" show-icon class="pc-login-alert">
            <template #title>登录失败</template>
            <div class="pc-login-alert-body">{{ error }}</div>
          </el-alert>

          <el-button
            type="primary"
            class="pc-login-submit"
            size="large"
            :loading="submitting"
            @click="submitCredentials"
          >
            登录
          </el-button>

          <p class="pc-login-foot">
            凭据来自主体表（<code class="pc-mono">paimon_principal</code>），与 Spark / Flink
            客户端调用本服务端用的是同一份。登录后令牌保存在本机浏览器，{{ ttlText }}后失效。<br />
            换回的是服务端签发的访问令牌，不是会话——服务端不保存登录状态，
            因此「退出登录」只是忘掉本机令牌。
          </p>
        </template>

        <!-- 方式三：OpenID Connect 授权码 + PKCE -->
        <template v-else-if="activeTab === 'oidc'">
          <el-alert v-if="error" type="error" :closable="false" show-icon class="pc-login-alert">
            <template #title>无法发起 SSO 登录</template>
            <div class="pc-login-alert-body">{{ error }}</div>
          </el-alert>

          <dl class="pc-login-kv">
            <dt>身份提供方</dt>
            <dd class="pc-mono pc-break">{{ auth.state.oidc?.issuer || '—' }}</dd>
            <dt>客户端</dt>
            <dd class="pc-mono">{{ auth.state.oidc?.clientId || '—' }}</dd>
            <dt>回调地址</dt>
            <dd class="pc-mono pc-break">{{ redirectUri || '—' }}</dd>
          </dl>

          <el-button
            type="primary"
            class="pc-login-submit"
            size="large"
            :loading="submitting"
            @click="submitSso"
          >
            使用 SSO 登录
          </el-button>

          <p class="pc-login-foot">
            将跳转到上面的身份提供方完成认证，再回到本控制台。
            本控制台是公开客户端，用 PKCE 换令牌，不保存任何客户端密钥。<br />
            服务端按 <code class="pc-mono">paimon.rest.auth.console.oidc.audience</code>
            校验令牌受众，用 ID token 时应填 IdP 里的 client-id。
          </p>
        </template>

        <!-- 降级入口：直接填一个静态令牌 -->
        <template v-else-if="activeTab === 'static-token'">
          <el-alert type="warning" :closable="false" show-icon class="pc-login-alert">
            <template #title>这是给脚本准备的降级入口</template>
            <div>
              需要自行到服务端 <code class="pc-mono">paimon.rest.auth.tokens</code>
              里取值。没有主体名，令牌本身就是主体名（除非配了
              <code class="pc-mono">token-principals</code> 映射）——
              换个人用就得改服务端配置，因此浏览器上更推荐上面三种方式。
            </div>
          </el-alert>

          <el-form label-position="top" class="pc-login-form" @submit.prevent>
            <el-form-item label="访问令牌">
              <el-input
                v-model="form.staticToken"
                size="large"
                type="password"
                show-password
                placeholder="服务端 paimon.rest.auth.tokens 中登记的值"
                :prefix-icon="Key"
                @keyup.enter="submitStaticToken"
              />
            </el-form-item>
          </el-form>

          <el-alert v-if="error" type="error" :closable="false" show-icon class="pc-login-alert">
            <template #title>服务端不认可这个令牌</template>
            <div class="pc-login-alert-body">{{ error }}</div>
          </el-alert>

          <el-button
            type="primary"
            class="pc-login-submit"
            size="large"
            :loading="submitting"
            @click="submitStaticToken"
          >
            保存并进入
          </el-button>

          <p class="pc-login-foot">
            令牌保存在本机浏览器。随时可在「<RouterLink :to="{ name: 'settings' }">连接设置</RouterLink>」里改。
          </p>
        </template>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Key, Lock, User } from '@element-plus/icons-vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { beginAuthorization, resolveRedirectUri } from '@/api/oidc.js'
import { AUTH_METHOD, auth } from '@/store/auth.js'
import { theme } from '@/store/theme.js'

const route = useRoute()
const router = useRouter()

const form = reactive({ username: '', password: '', clientId: '', clientSecret: '', staticToken: '' })
const submitting = ref(false)
const error = ref('')
const activeTab = ref(AUTH_METHOD.PASSWORD)

const TAB_LABELS = {
  [AUTH_METHOD.PASSWORD]: '用户名密码',
  [AUTH_METHOD.CLIENT_CREDENTIALS]: '主体凭据',
  [AUTH_METHOD.OIDC]: 'SSO 单点登录',
  [AUTH_METHOD.STATIC_TOKEN]: '静态令牌',
}

/**
 * 页签由服务端下发的 `methods` 生成，顺序照搬——服务端把静态令牌排在最后，
 * 页面就把它放在最后。前端不再自己决定「该推荐哪种」。
 */
const tabs = computed(() => auth.state.methods
  .filter((method) => TAB_LABELS[method])
  .map((method) => ({ name: method, label: TAB_LABELS[method] })))

const mode = computed(() => {
  if (!auth.state.checked) {
    return auth.state.error ? 'unreachable' : 'checking'
  }
  // 服务端既不看令牌也不要求控制台登录，就没有登录可言。
  // methods 为空也走这里——列一个点不动的登录方式比直接说「不需要登录」更难排查
  if (!auth.loginRequired() || tabs.value.length === 0) {
    return 'not-required'
  }
  return 'form'
})

const redirectUri = computed(() => (auth.state.oidc ? resolveRedirectUri(auth.state.oidc) : ''))

/** 令牌有效期；服务端下发的方法里没有它，因此按 OIDC 响应或访问令牌的 TTL 说明。 */
const ttlText = computed(() => {
  const seconds = auth.expiresInSeconds()
  if (!seconds) return '有效期由服务端 access-token.ttl 决定'
  if (seconds % 3600 === 0) return `${seconds / 3600} 小时`
  if (seconds % 60 === 0) return `${seconds / 60} 分钟`
  return `${seconds} 秒`
})

/**
 * 登录后要去的地方。
 *
 * <p>只接受站内路径：`redirect` 会原样交给 router.replace，
 * 放行一个绝对 URL 等于开放重定向。
 */
function safeRedirect() {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : ''
}

function enterConsole() {
  router.replace(safeRedirect() || { name: 'dashboard' })
}

function clearMessages() {
  error.value = ''
}

async function submitPassword() {
  if (submitting.value) return
  clearMessages()
  if (!form.username.trim() || !form.password) {
    error.value = '请填写用户名与密码'
    return
  }
  submitting.value = true
  const result = await auth.loginWithPassword(form.username.trim(), form.password)
  submitting.value = false
  if (!result.ok) {
    error.value = result.error
    // 只清密码，保留用户名：失败的原因更可能是密码，让人重打一遍用户名没有意义
    form.password = ''
    return
  }
  enterConsole()
}

async function submitCredentials() {
  if (submitting.value) return
  clearMessages()
  if (!form.clientId.trim() || !form.clientSecret) {
    error.value = '请填写主体标识与主体密钥'
    return
  }
  submitting.value = true
  const result = await auth.loginWithClientCredentials(form.clientId.trim(), form.clientSecret)
  submitting.value = false
  if (!result.ok) {
    error.value = result.error
    form.clientSecret = ''
    return
  }
  enterConsole()
}

async function submitSso() {
  if (submitting.value) return
  clearMessages()
  submitting.value = true
  try {
    // 整页跳走，因此不需要复位 submitting：要么离开本页，要么抛错
    window.location.assign(await beginAuthorization(auth.state.oidc, safeRedirect()))
  } catch (cause) {
    submitting.value = false
    error.value = cause?.message || String(cause)
  }
}

async function submitStaticToken() {
  if (submitting.value) return
  clearMessages()
  if (!form.staticToken.trim()) {
    error.value = '请填写访问令牌'
    return
  }
  submitting.value = true
  const result = await auth.loginWithStaticToken(form.staticToken.trim())
  submitting.value = false
  if (!result.ok) {
    error.value = result.error
    return
  }
  enterConsole()
}

async function retry() {
  clearMessages()
  if (await auth.refresh()) {
    if (auth.state.authenticated) {
      enterConsole()
    }
  }
}

// 页签在服务端应答之后才有值：默认落在第一个可用方式上，
// 并保证当前选中的那个确实还在列表里（服务端配置可能变了）
watch(tabs, (list) => {
  if (!list.some((tab) => tab.name === activeTab.value)) {
    activeTab.value = list[0]?.name || ''
  }
}, { immediate: true })

onMounted(async () => {
  if (!auth.state.checked) {
    await auth.refresh()
  }
  // 带着有效令牌直接访问登录页（书签、后退）：没有理由停在表单上
  if (auth.state.authenticated) {
    enterConsole()
  }
})
</script>

<style scoped>
.pc-login {
  position: relative;
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 32px 20px;
  background:
    radial-gradient(1100px 460px at 12% -12%, rgba(29, 78, 216, 0.14), transparent 62%),
    var(--pc-page-bg);
}

.pc-login-theme {
  position: absolute;
  top: 16px;
  right: 16px;
}

.pc-login-card {
  width: 100%;
  max-width: 460px;
  padding: 32px 32px 28px;
  background: var(--pc-surface);
  border: 1px solid var(--pc-border);
  border-radius: 14px;
  box-shadow: 0 10px 30px rgba(16, 24, 40, 0.09), 0 2px 6px rgba(16, 24, 40, 0.05);
}

.pc-login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}

.pc-login-mark {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: linear-gradient(140deg, #3b82f6, #1d4ed8);
  color: #fff;
  font-weight: 700;
  font-size: 20px;
  flex-shrink: 0;
}

.pc-login-name {
  font-size: 19px;
  font-weight: 600;
  letter-spacing: 0.2px;
}

.pc-login-sub {
  margin-top: 2px;
  color: var(--pc-text-dim);
  font-size: 13px;
}

.pc-login-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.pc-login-single {
  margin-bottom: 16px;
  font-size: 14px;
  font-weight: 600;
  color: var(--pc-text);
}

.pc-login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.pc-login-form :deep(.el-form-item__label) {
  font-size: 13.5px;
  color: var(--pc-text);
  padding-bottom: 4px;
}

.pc-login-alert {
  margin-bottom: 16px;
}

.pc-login-alert-body {
  word-break: break-word;
}

.pc-login-submit {
  width: 100%;
}

.pc-login-hint {
  color: var(--pc-text-dim);
  font-size: 14px;
  padding: 8px 0 4px;
}

.pc-login-kv {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px 14px;
  margin: 0 0 18px;
  font-size: 12.5px;
}

.pc-login-kv dt {
  color: var(--pc-text-dim);
  white-space: nowrap;
}

.pc-login-kv dd {
  margin: 0;
}

.pc-login-foot {
  margin: 16px 0 0;
  color: var(--pc-text-dim);
  font-size: 12.5px;
  line-height: 1.7;
  text-align: center;
}

.pc-login-foot a {
  color: var(--el-color-primary);
  text-decoration: none;
}

.pc-login-foot a:hover {
  text-decoration: underline;
}
</style>
