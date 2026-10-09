<template>
  <div class="pc-callback">
    <div class="pc-callback-card">
      <div class="pc-callback-brand">
        <div class="pc-callback-mark">P</div>
        <div>
          <div class="pc-callback-name">Paimon Rest Catalog</div>
          <div class="pc-callback-sub">正在完成登录</div>
        </div>
      </div>

      <template v-if="phase === 'working'">
        <div class="pc-callback-hint">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>{{ status }}</span>
        </div>
      </template>

      <template v-else-if="phase === 'failed'">
        <el-alert type="error" :closable="false" show-icon class="pc-callback-alert">
          <template #title>SSO 登录未完成</template>
          <div class="pc-callback-alert-body">{{ error }}</div>
        </el-alert>
        <el-button type="primary" class="pc-callback-submit" size="large" @click="backToLogin">
          返回登录页
        </el-button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'

import { clearPending, completeAuthorization } from '@/api/oidc.js'
import { auth } from '@/store/auth.js'

/**
 * OIDC 回调页。
 *
 * <p>它是一次**整页跳转**的终点：IdP 认证完把浏览器送到这里，带着授权码。
 * 这一步必须在浏览器里做，因为 PKCE 的 `code_verifier` 只在本标签页的
 * sessionStorage 里，服务端没有它——这正是把授权码换成令牌的权利绑在
 * 「发起登录的那个浏览器」上的手段。
 *
 * <p>换到令牌之后**不直接放行**，而是交给服务端验一次
 * （{@code auth.adopt} 会去问 {@code /api/console/v1/auth}）。
 * 受众不匹配、签发者不对、主体取不到——这三种失败在浏览器里都看不出来，
 * 只有服务端知道。在这里挡住，比让人带着废令牌进控制台、然后到处 401 要清楚得多。
 */
const route = useRoute()
const router = useRouter()

const phase = ref('working')
const status = ref('正在换取令牌…')
const error = ref('')

function backToLogin() {
  router.replace({ name: 'login' })
}

/** 从 URL 查询串里取一个字符串参数。 */
function query(name) {
  const value = route.query[name]
  return typeof value === 'string' ? value : ''
}

async function complete() {
  // IdP 拒绝授权（用户点了取消、scope 未批准等）：错误在查询串里，没有 code 可换
  const idpError = query('error')
  if (idpError) {
    clearPending()
    // 用户主动取消不是故障，措辞上不要说得像出错
    const description = query('error_description')
    error.value = idpError === 'access_denied'
      ? '身份提供方拒绝了这次授权（通常是点了取消，或该账号未被允许使用此应用）'
      : `${idpError}${description ? `：${description}` : ''}`
    phase.value = 'failed'
    return
  }

  const code = query('code')
  if (!code) {
    clearPending()
    error.value = '回调地址里没有授权码。直接访问本地址是无效的，请从登录页发起 SSO 登录。'
    phase.value = 'failed'
    return
  }

  // OIDC 的端点由服务端下发，因此先问一次。守卫不拦本页（meta.public），
  // 所以这一步得自己做
  if (!(await auth.ensureChecked(true))) {
    clearPending()
    error.value = `无法读取服务端的 OIDC 配置：${auth.state.error}`
    phase.value = 'failed'
    return
  }
  if (!auth.state.oidc) {
    clearPending()
    error.value = '服务端当前没有启用 OIDC 登录方式，无法完成这次回调。'
    phase.value = 'failed'
    return
  }

  let issued
  try {
    issued = await completeAuthorization(auth.state.oidc, { code, state: query('state') })
  } catch (cause) {
    error.value = cause?.message || String(cause)
    phase.value = 'failed'
    return
  }

  status.value = '正在让服务端确认这个令牌…'
  const adopted = await auth.adopt(issued.token)
  if (!adopted.ok) {
    error.value = adopted.error
    phase.value = 'failed'
    return
  }

  // 只接受站内路径：`returnTo` 是发起登录时写进 sessionStorage 的，
  // 但 sessionStorage 属于本页面源，仍然按不可信输入处理
  const returnTo = issued.returnTo
  router.replace(
    returnTo && returnTo.startsWith('/') && !returnTo.startsWith('//')
      ? returnTo
      : { name: 'dashboard' },
  )
}

onMounted(complete)
</script>

<style scoped>
.pc-callback {
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 32px 20px;
  background:
    radial-gradient(1100px 460px at 12% -12%, rgba(29, 78, 216, 0.14), transparent 62%),
    var(--pc-page-bg);
}

.pc-callback-card {
  width: 100%;
  max-width: 460px;
  padding: 32px;
  background: var(--pc-surface);
  border: 1px solid var(--pc-border);
  border-radius: 14px;
  box-shadow: 0 10px 30px rgba(16, 24, 40, 0.09), 0 2px 6px rgba(16, 24, 40, 0.05);
}

.pc-callback-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}

.pc-callback-mark {
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

.pc-callback-name {
  font-size: 19px;
  font-weight: 600;
}

.pc-callback-sub {
  margin-top: 2px;
  color: var(--pc-text-dim);
  font-size: 13px;
}

.pc-callback-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--pc-text-dim);
  font-size: 14px;
}

.pc-callback-alert {
  margin-bottom: 16px;
}

.pc-callback-alert-body {
  word-break: break-word;
}

.pc-callback-submit {
  width: 100%;
}
</style>
