<template>
  <div class="pc-shell">
    <aside class="pc-sidebar pc-scroll">
      <div class="pc-brand">
        <div class="pc-brand-mark">P</div>
        <div class="pc-brand-text">
          <div class="pc-brand-name">Paimon Rest Catalog</div>
          <div class="pc-brand-sub">管理控制台</div>
        </div>
      </div>

      <nav class="pc-nav">
        <div v-for="group in groups" :key="group.group" class="pc-nav-group">
          <div class="pc-nav-label">{{ group.group }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.name"
            :to="item.path"
            class="pc-nav-item"
            :class="{ 'is-active': isActive(item) }"
          >
            <el-icon class="pc-nav-icon"><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="pc-sidebar-foot">
        <div class="pc-foot-line">
          <span class="pc-foot-key">服务端</span>
          <span class="pc-foot-val pc-mono">{{ credentials.baseUrl || '同源' }}</span>
        </div>
        <div class="pc-foot-line">
          <span class="pc-foot-key">令牌</span>
          <span class="pc-foot-val">
            <el-tag v-if="credentials.configured" size="small" type="success" effect="dark">已配置</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">未配置</el-tag>
          </span>
        </div>
      </div>
    </aside>

    <div class="pc-main">
      <header class="pc-header">
        <el-breadcrumb separator="/" class="pc-crumbs">
          <el-breadcrumb-item v-for="(item, index) in trail" :key="index" :to="item.to">
            {{ item.text }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <div class="pc-spacer" />

        <div class="pc-header-ctl">
          <span class="pc-header-label">当前 Catalog</span>
          <el-select
            :model-value="session.state.selectedCatalog"
            class="pc-catalog-select"
            size="small"
            placeholder="未选择"
            :loading="session.state.loadingCatalogs"
            @update:model-value="onSelectCatalog"
          >
            <el-option v-for="catalog in session.state.catalogs" :key="catalog.name" :label="catalog.name" :value="catalog.name" />
          </el-select>
        </div>

        <el-tooltip :content="theme.state.mode === 'dark' ? '切换到浅色' : '切换到深色'" placement="bottom">
          <el-button size="small" text circle @click="theme.toggle()">
            <el-icon><component :is="theme.state.mode === 'dark' ? 'Sunny' : 'Moon'" /></el-icon>
          </el-button>
        </el-tooltip>

        <el-tooltip content="重新加载当前页面数据" placement="bottom">
          <el-button size="small" text circle @click="refresh">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </el-tooltip>

        <!-- 服务端认可手上的令牌时才显示「你是谁」。
             令牌来自哪条路径（客户端凭据 / SSO / 手填的静态令牌）放在提示里：
             「退出登录」只是忘掉本机这份令牌，说清楚来源才不至于让人以为
             自己注销了某个服务端会话（服务端并没有会话） -->
        <div v-if="auth.state.authenticated" class="pc-header-user">
          <span class="pc-header-divider" />
          <el-tooltip :content="`令牌来源：${auth.state.sourceLabel}`" placement="bottom">
            <el-icon class="pc-header-user-icon"><UserFilled /></el-icon>
          </el-tooltip>
          <span class="pc-header-user-name">{{ auth.state.principal }}</span>
          <el-tooltip content="退出登录（忘掉本机保存的令牌）" placement="bottom">
            <el-button size="small" text circle @click="logout">
              <el-icon><SwitchButton /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </header>

      <main class="pc-content pc-scroll">
        <slot />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { navGroups } from '@/router/index.js'
import { REFRESH_EVENT } from '@/composables/index.js'
import { auth } from '@/store/auth.js'
import { credentials } from '@/store/credentials.js'
import { session } from '@/store/session.js'
import { theme } from '@/store/theme.js'

const route = useRoute()
const router = useRouter()

const groups = navGroups()

/** 当前菜单高亮：自身命中，或本页声明了 `meta.parent` 指向它。 */
function isActive(item) {
  return route.name === item.name || route.meta?.parent === item.name
}

/**
 * 面包屑。分组名不进链接——分组本身不是页面，写成链接会指向 404。
 * 表详情页把「目录浏览」作为上一级，带上当前的查询串以免回去时丢了选中的目录。
 */
const trail = computed(() => {
  const meta = route.meta || {}
  const items = [{ text: '控制台', to: { name: 'dashboard' } }]
  if (meta.group && meta.group !== '概览') {
    items.push({ text: meta.group })
  }
  if (meta.parent) {
    const parent = router.getRoutes().find((candidate) => candidate.name === meta.parent)
    if (parent) {
      items.push({ text: parent.meta?.title || meta.parent, to: { name: meta.parent, query: route.query } })
    }
  }
  items.push({ text: meta.title || '未找到' })
  return items
})

function onSelectCatalog(name) {
  session.selectCatalog(name)
  // 切了目录，当前页面展示的资源多半不在新目录里，回到浏览页更符合预期；
  // 其余页面各自重新加载即可（刷新广播）。
  if (route.meta?.parent === 'browse') {
    router.push({ name: 'browse', query: { catalog: name } })
    return
  }
  refresh()
}

function refresh() {
  session.loadCatalogs().catch(() => {})
  window.dispatchEvent(new Event(REFRESH_EVENT))
}

/**
 * 退出登录。
 *
 * <p>会话与「当前目录、服务端元数据」这些缓存一起清掉：它们是在上一个身份的
 * 权限下取回来的，留着会让下一个登录者看到不属于自己的列表。
 *
 * <p><b>它只是忘掉本机令牌</b>——服务端不保存登录状态（令牌是自包含的 JWT），
 * 没有可以作废的东西。令牌在自身的有效期内仍然有效，这一点写在按钮提示里，
 * 不让人误以为已经把自己踢下线了。
 *
 * <p>登出后是否跳登录页取决于服务端：不需要令牌的部署里，登出只是清掉本机令牌，
 * 把人赶到登录页反而奇怪（那里会告诉他「服务端未要求登录」）。
 */
async function logout() {
  await auth.logout()
  session.reset()
  if (auth.loginRequired()) {
    router.replace({ name: 'login' })
    return
  }
  refresh()
}

onMounted(() => {
  // 数据加载放在这里而不是 main.js：需要登录的服务端上，登录页不该发这些请求
  // （只会拿到两个 401，还会在概览页留下两条并不成立的错误提示）。
  // 本布局只在非 public 路由下渲染，所以到达这里就意味着已经过了登录守卫。
  session.bootstrap()
})
</script>

<style scoped>
.pc-shell {
  display: flex;
  height: 100%;
}

/* --- 侧边栏 ----------------------------------------------------------- */

.pc-sidebar {
  width: var(--pc-sidebar-w);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--pc-sidebar-bg);
  color: var(--pc-sidebar-fg);
  overflow-y: auto;
}

.pc-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 18px 16px;
}

.pc-brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: linear-gradient(140deg, #3b82f6, #1d4ed8);
  color: #fff;
  font-weight: 700;
  font-size: 17px;
}

.pc-brand-name {
  font-size: 15px;
  font-weight: 600;
  color: #eaf0fa;
  letter-spacing: 0.3px;
}

.pc-brand-sub {
  font-size: 13px;
  color: var(--pc-sidebar-dim);
  margin-top: 1px;
}

.pc-nav {
  flex: 1;
  padding: 8px 12px 18px;
}

.pc-nav-group + .pc-nav-group {
  margin-top: 18px;
}

.pc-nav-label {
  padding: 0 10px 8px;
  font-size: 12.5px;
  letter-spacing: 0.8px;
  text-transform: uppercase;
  color: var(--pc-sidebar-dim);
}

.pc-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  margin-bottom: 2px;
  border-radius: 7px;
  color: var(--pc-sidebar-fg);
  text-decoration: none;
  font-size: 14px;
  transition: background 0.15s ease, color 0.15s ease;
}

.pc-nav-item:hover {
  background: var(--pc-sidebar-hover);
}

.pc-nav-item.is-active {
  background: var(--pc-sidebar-active);
  color: var(--pc-sidebar-active-fg);
  font-weight: 600;
}

.pc-nav-icon {
  font-size: 16px;
  opacity: 0.9;
}

.pc-sidebar-foot {
  padding: 14px 18px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
  font-size: 12.5px;
  color: var(--pc-sidebar-dim);
}

.pc-foot-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 26px;
}

.pc-foot-val {
  color: var(--pc-sidebar-fg);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* --- 主区 ------------------------------------------------------------- */

.pc-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.pc-header {
  height: 58px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 24px;
  background: var(--pc-surface);
  border-bottom: 1px solid var(--pc-border);
}

.pc-crumbs {
  font-size: 13.5px;
}

.pc-header-ctl {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pc-header-label {
  font-size: 13px;
  color: var(--pc-text-dim);
}

.pc-catalog-select {
  width: 176px;
}

/* 登录用户。只在服务端认可一个会话时出现（见模板里的说明） */
.pc-header-user {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pc-header-divider {
  width: 1px;
  height: 20px;
  margin: 0 6px;
  background: var(--pc-border);
}

.pc-header-user-icon {
  font-size: 17px;
  color: var(--pc-text-dim);
}

.pc-header-user-name {
  font-size: 13.5px;
  font-weight: 600;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pc-content {
  flex: 1;
  overflow-y: auto;
}
</style>
