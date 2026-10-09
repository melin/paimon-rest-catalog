<template>
  <div class="pc-page">
    <PageHeader
      title="连接设置"
      subtitle="控制台把连接信息保存在本机浏览器（localStorage），不会发送到服务端保存。多人共用一台机器时请注意令牌会留在浏览器里。"
    />

    <PanelCard title="服务端地址与访问令牌">
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item>
          <template #label>
            <FieldLabel label="API 基址（baseUrl）">
              只有在把控制台单独部署（例如放到 CDN）时才需要填。填了之后所有请求会打到
              <code>&lt;baseUrl&gt;/v1/...</code> 与 <code>&lt;baseUrl&gt;/api/...</code>，
              此时服务端必须允许跨域。
            </FieldLabel>
          </template>
          <el-input v-model="form.baseUrl" placeholder="留空表示同源，即控制台与 API 在同一地址" />
        </el-form-item>
        <el-form-item>
          <template #label>
            <FieldLabel label="访问令牌（Bearer token）">
              <p>
                服务端 <code>paimon.rest.auth.enabled=false</code>（默认）时，数据接口
                （<code>/v1/**</code>、<code>/api/management/v1/**</code>）不需要填；
                开启后它们一律要求 <code>Authorization: Bearer</code>。
                若配置了 <code>token-principals</code>，令牌会被映射成对应的主体名，
                授权判定按主体名进行。
              </p>
              <p>
                <strong>这里和登录页写的是同一个位置。</strong>
                登录页用账号密码、主体凭据或 SSO 换回来的令牌就存在此处；
                它们只是来源不同，填了一边就覆盖另一边。<br />
                反过来说：在这里手填令牌等于跳过登录页——服务端只关心请求头里的令牌，
                并不关心它是怎么来的。
              </p>
              <p>
                注意控制台自身另有一层门禁 <code>paimon.rest.auth.console.required</code>
                （默认 <code>true</code>）：即使 <code>auth.enabled=false</code>，
                <code>/api/console/v1/**</code> 也要求令牌，因此不登录时上面「控制台元数据」
                那一项探测必然失败——那不是配置错了。
              </p>
            </FieldLabel>
          </template>
          <el-input v-model="form.token" type="password" show-password placeholder="服务端 paimon.rest.auth.tokens 中登记的令牌" />
        </el-form-item>
      </el-form>

      <div class="pc-form-actions">
        <el-button size="small" type="primary" :loading="testing" @click="saveAndTest">
          <el-icon><Connection /></el-icon>
          <span class="pc-btn-text">保存并测试连接</span>
        </el-button>
        <el-button size="small" @click="test">仅测试当前配置</el-button>
        <el-button size="small" type="danger" plain @click="clear">清空本地设置</el-button>
      </div>

      <div v-if="probes.length" class="pc-probes">
        <div v-for="probe in probes" :key="probe.label" class="pc-probe">
          <el-tag size="small" :type="probe.ok ? 'success' : 'danger'" effect="plain">
            {{ probe.ok ? '通过' : '失败' }}
          </el-tag>
          <span class="pc-probe-label">{{ probe.label }}</span>
          <span class="pc-mono pc-dim">{{ probe.path }}</span>
          <span v-if="probe.detail" class="pc-probe-detail">{{ probe.detail }}</span>
        </div>
      </div>
    </PanelCard>

    <PanelCard title="登录状态" hint="GET /api/console/v1/auth">
      <dl class="pc-kv">
        <dt>服务端鉴权</dt>
        <dd>
          <el-tag size="small" :type="auth.state.authEnabled ? 'success' : 'info'" effect="plain">
            {{ auth.state.authEnabled ? '要求 Bearer 令牌' : '不校验令牌' }}
          </el-tag>
          <span class="pc-dim">
            （<code class="pc-mono">paimon.rest.auth.enabled</code>，管
            <code class="pc-mono">/v1/**</code> 与管理 API）
          </span>
        </dd>
        <dt>控制台门禁</dt>
        <dd>
          <el-tag size="small" :type="auth.state.consoleRequired ? 'success' : 'info'" effect="plain">
            {{ auth.state.consoleRequired ? '必须先登录' : '不要求登录' }}
          </el-tag>
          <span v-if="auth.gateOnly()" class="pc-dim">
            （只挡界面：数据接口仍然匿名可调，<strong>不是安全边界</strong>）
          </span>
        </dd>
        <dt>可用登录方式</dt>
        <dd>{{ methodText }}</dd>
        <dt>当前身份</dt>
        <dd>
          <template v-if="auth.state.authenticated">
            <span class="pc-mono">{{ auth.state.principal }}</span>
            <span class="pc-dim">（{{ auth.state.sourceLabel }}，{{ ttlText }}）</span>
          </template>
          <span v-else class="pc-dim">未登录（当前令牌未被服务端认可）</span>
        </dd>
        <dt>令牌端点</dt>
        <dd class="pc-mono">{{ auth.state.tokenEndpoint || '—' }}</dd>
      </dl>

      <div class="pc-form-actions">
        <el-button v-if="auth.state.authenticated" size="small" type="primary" plain @click="doLogout">
          <el-icon><SwitchButton /></el-icon>
          <span class="pc-btn-text">退出登录</span>
        </el-button>
        <el-button v-if="!auth.state.authenticated" size="small" type="primary" @click="goLogin">
          <el-icon><Key /></el-icon>
          <span class="pc-btn-text">去登录</span>
        </el-button>
        <el-button size="small" :loading="auth.state.checking" @click="auth.refresh()">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">重新检查</span>
        </el-button>
      </div>

      <div class="pc-tip">
        <strong>退出登录只是忘掉本机保存的令牌。</strong>
        服务端不保存登录状态——令牌是自包含的 JWT，签出去之后在有效期
        （<code class="pc-mono">paimon.rest.auth.access-token.ttl</code>）内一直有效，
        没有可以作废它的接口；要立刻让某人的令牌失效只能轮换
        <code class="pc-mono">access-token.signing-key</code>，而那会作废所有人的令牌。
      </div>
      <div class="pc-tip">
        <strong>控制台门禁不是安全边界。</strong>
        <code class="pc-mono">paimon.rest.auth.console.required</code>
        （默认 <code class="pc-mono">true</code>）只让浏览器必须先登录；
        <code class="pc-mono">/v1/**</code> 与管理 API 是否要求令牌，仍由
        <code class="pc-mono">paimon.rest.auth.enabled</code>
        （默认 <code class="pc-mono">false</code>）决定。要保护数据请开启后者——
        光靠门禁挡不住 <code class="pc-mono">curl</code>。
      </div>
      <div class="pc-tip">
        登录方式由服务端下发，顺序即登录页的页签顺序。
        <code class="pc-mono">paimon.rest.auth.console.password</code>
        （用户名 + 密码，默认开启，账号 <code class="pc-mono">admin/admin</code>）
        排在第一位——它不需要先建主体，装好就能进。
        接下来是对标 Polaris Console 的
        <code class="pc-mono">…console.client-credentials</code>
        （主体凭据换令牌，默认开启）与
        <code class="pc-mono">paimon.rest.auth.console.oidc</code>
        （外部 IdP 授权码 + PKCE）。这三条都只产出一个 Bearer 令牌，
        之后走同一条通道，服务端也不记录它来自哪条。
        <code class="pc-mono">paimon.rest.auth.tokens</code>
        里的静态令牌排最后，是给脚本用的降级入口。
      </div>
      <div class="pc-tip">
        OIDC 登录时，服务端按 <code class="pc-mono">oidc.principal-claim</code>
        （默认 <code class="pc-mono">sub</code>）取主体名。取到的值要能与
        <code class="pc-mono">paimon_principal.name</code> 或
        <code class="pc-mono">authorization.service-admins</code> 里的名字对上，
        否则登录成功但什么也看不到。
      </div>
    </PanelCard>

    <PanelCard title="界面">
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="主题">
          <el-radio-group :model-value="theme.state.mode" @update:model-value="theme.set">
            <el-radio-button value="light">浅色</el-radio-button>
            <el-radio-button value="dark">深色</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
    </PanelCard>

    <PanelCard title="服务端信息" hint="GET /api/console/v1/meta">
      <ResourceState
        :loading="session.state.loadingMeta"
        :error="session.state.metaError"
        :empty="false"
      >
        <dl class="pc-kv">
          <dt>服务名</dt>
          <dd>{{ meta?.service?.name || '—' }}</dd>
          <dt>版本</dt>
          <dd class="pc-mono">{{ meta?.service?.version || '—' }}</dd>
          <dt>默认 prefix</dt>
          <dd class="pc-mono">{{ meta?.service?.defaultPrefix || '—' }}</dd>
          <dt>默认仓库</dt>
          <dd class="pc-mono pc-break">{{ meta?.service?.defaultWarehouse || '—' }}</dd>
          <dt>表路径模板</dt>
          <dd class="pc-mono">{{ meta?.service?.pathTemplate || '—' }}</dd>
          <dt>存储实现</dt>
          <dd class="pc-mono pc-break">{{ (meta?.enums?.supportedStorageTypes || []).join(' / ') || '—' }}</dd>
          <dt>凭据下发</dt>
          <dd class="pc-mono">{{ meta?.service?.credentialManagerType || '—' }}</dd>
        </dl>
      </ResourceState>
    </PanelCard>

    <PanelCard title="关于控制台">
      <dl class="pc-kv">
        <dt>控制台版本</dt>
        <dd class="pc-mono">{{ pkg.version }}</dd>
        <dt>前端栈</dt>
        <dd>Vue 3 · Vue Router · Element Plus · Vite</dd>
        <dt>构建产物</dt>
        <dd class="pc-mono">paimon-rest-server/src/main/resources/static/console</dd>
        <dt>源码</dt>
        <dd class="pc-mono">paimon-rest-console/</dd>
        <dt>接口范围</dt>
        <dd>
          catalog API（<code class="pc-mono">/v1/**</code>）与管理 API（
          <code class="pc-mono">/api/management/v1/**</code>），
          另加一个只读的元数据端点 <code class="pc-mono">/api/console/v1/meta</code>。
        </dd>
      </dl>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import { catalogApi } from '@/api/catalog.js'
import { describeError } from '@/api/client.js'
import { managementApi, metaApi } from '@/api/management.js'
import { auth } from '@/store/auth.js'
import { credentials } from '@/store/credentials.js'
import { session } from '@/store/session.js'
import { theme } from '@/store/theme.js'
import pkg from '../../package.json'

const router = useRouter()

const form = reactive({ baseUrl: credentials.baseUrl, token: credentials.token })
const testing = ref(false)
const probes = ref([])

const meta = computed(() => session.state.meta)

/** 登录方式的可读名，取值与服务端 `ConsoleDtos.AuthMethod` 对应。 */
const METHOD_LABELS = {
  password: '用户名密码（服务端配置的账号，默认 admin/admin）',
  'client-credentials': '主体凭据（OAuth 2.0 客户端凭据）',
  oidc: 'SSO 单点登录（OIDC）',
  'static-token': '静态令牌（降级入口）',
}

/** 登录方式的措辞与登录页保持一致，避免两处说不同的话。 */
const methodText = computed(() => {
  if (!auth.loginRequired()) {
    return '不适用（服务端既不校验令牌也不要求控制台登录，登录页不会被用到）'
  }
  const names = auth.state.methods.map((method) => METHOD_LABELS[method] || method)
  return names.length ? names.join('、') : '未配置任何登录方式'
})

const ttlText = computed(() => {
  const seconds = auth.expiresInSeconds()
  if (seconds === null) return '静态令牌，无失效时刻'
  if (seconds <= 0) return '已过期'
  if (seconds % 3600 === 0) return `剩余 ${seconds / 3600} 小时`
  if (seconds % 60 === 0) return `剩余 ${seconds / 60} 分钟`
  return `剩余 ${seconds} 秒`
})

function goLogin() {
  router.push({ name: 'login' })
}

async function doLogout() {
  await auth.logout()
  session.reset()
  form.token = ''
  probes.value = []
  if (auth.loginRequired()) {
    router.replace({ name: 'login' })
  }
}

/**
 * 逐个探测控制台依赖的端点。
 *
 * <p>三次探测互相独立（不用 `Promise.all` 的整体 reject 语义），
 * 某一条失败不影响其余的结果——只报「连接失败」而不指出是哪一段，
 * 反而要多试几轮才能定位。
 */
async function runProbes() {
  testing.value = true
  const results = []
  const steps = [
    { label: '控制台元数据', path: 'GET /api/console/v1/meta', run: () => metaApi.meta() },
    { label: '管理 API', path: 'GET /api/management/v1/catalogs', run: () => managementApi.listCatalogs() },
    { label: 'catalog API', path: 'GET /v1/config', run: () => catalogApi.config() },
  ]
  for (const step of steps) {
    try {
      await step.run()
      results.push({ ...step, ok: true, detail: '' })
    } catch (error) {
      results.push({ ...step, ok: false, detail: describeError(error) })
    }
  }
  probes.value = results
  testing.value = false
  session.loadMeta()
  session.loadCatalogs().catch(() => {})
}

function test() {
  runProbes()
}

async function saveAndTest() {
  credentials.save(form.token.trim(), form.baseUrl.trim())
  await runProbes()
}

function clear() {
  credentials.clear()
  form.token = ''
  form.baseUrl = ''
  probes.value = []
  session.reset()
}
</script>

<style scoped>
.pc-form {
  max-width: 620px;
}

.pc-form-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.pc-probes {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pc-probe {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  flex-wrap: wrap;
}

.pc-probe-label {
  font-weight: 600;
}

.pc-probe-detail {
  color: var(--el-color-danger);
  font-size: 12.5px;
  flex-basis: 100%;
  padding-left: 56px;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
