<template>
  <div class="pc-page">
    <PageHeader
      title="控制台概览"
      subtitle="服务端能力、目录清单与连接自检。所有数据都来自服务端接口，未在本地缓存。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="pc-stats">
      <div v-for="item in stats" :key="item.label" class="pc-stat">
        <div class="pc-stat-label">{{ item.label }}</div>
        <div class="pc-stat-value">{{ item.value }}</div>
        <div class="pc-stat-foot">{{ item.foot }}</div>
      </div>
    </div>

    <ResourceState :loading="loading" :error="error" />

    <div class="pc-grid">
      <PanelCard title="服务端配置" hint="来自 paimon.rest.* 的生效值">
        <dl class="pc-kv">
          <template v-for="row in serviceRows" :key="row.label">
            <dt>{{ row.label }}</dt>
            <dd>
              <el-tag v-if="row.tag" size="small" :type="row.tagType" effect="plain">{{ row.tag }}</el-tag>
              <span v-else class="pc-mono">{{ row.value }}</span>
            </dd>
          </template>
        </dl>
      </PanelCard>

      <PanelCard title="连接自检" hint="逐个探测控制台依赖的端点">
        <div class="pc-stack">
          <div v-for="check in checks" :key="check.label" class="pc-check">
            <el-tag size="small" :type="check.state.type" effect="plain" class="pc-check-tag">
              {{ check.state.text }}
            </el-tag>
            <div class="pc-check-body">
              <div class="pc-check-label">{{ check.label }}</div>
              <div class="pc-mono pc-dim">{{ check.path }}</div>
              <div v-if="check.detail" class="pc-dim pc-check-detail">{{ check.detail }}</div>
            </div>
          </div>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="目录清单" :hint="`共 ${session.state.catalogs.length} 个`" flush>
      <template #actions>
        <el-button size="small" text type="primary" @click="$router.push({ name: 'catalogs' })">
          管理
        </el-button>
      </template>
      <ResourceState
        :loading="session.state.loadingCatalogs"
        :error="session.state.catalogsError"
        :empty="!session.state.catalogs.length"
        empty-text="服务端还没有登记任何 catalog"
      >
        <el-table :data="session.state.catalogs" size="small">
          <el-table-column label="名称" min-width="150">
            <template #default="{ row }">
              <RouterLink class="pc-link" :to="{ name: 'browse', query: { catalog: row.name } }">
                {{ row.name }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column label="存储类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ row.storageConfigInfo?.storageType || '—' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="仓库位置" min-width="220">
            <template #default="{ row }">
              <span class="pc-mono pc-break">
                {{ row.storageConfigInfo?.allowedLocations?.[0] || row.properties?.warehouse || '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">
              <TimeText :millis="row.lastUpdateTimestamp" mode="relative" />
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </PanelCard>

    <PanelCard
      v-if="configData"
      title="目录发现结果"
      hint="GET /v1/config"
      flush
    >
      <div class="pc-config">
        <div>
          <div class="pc-config-label">defaults</div>
          <JsonBlock :value="configData.defaults" max-height="220px" />
        </div>
        <div>
          <div class="pc-config-label">overrides</div>
          <JsonBlock :value="configData.overrides" max-height="220px" />
        </div>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TimeText from '@/components/TimeText.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import { getBaseUrl } from '@/api/client.js'
import { catalogApi } from '@/api/catalog.js'
import { managementApi } from '@/api/management.js'
import { onConsoleRefresh, useResource } from '@/composables/index.js'
import { auth } from '@/store/auth.js'
import { session } from '@/store/session.js'
import { credentials } from '@/store/credentials.js'
import { formatNumber } from '@/utils/format.js'

/**
 * 概览页。
 *
 * <p>「连接自检」不是装饰：控制台最常见的故障是地址或令牌配错，
 * 而症状在各页面上是「空列表」，看不出是没数据还是没连上。
 * 这里把三个依赖端点的探测结果直接摆出来，省掉一轮猜。
 */
const directory = useResource(async () => {
  const [principals, principalRoles] = await Promise.all([
    managementApi.listPrincipals(),
    managementApi.listPrincipalRoles(),
  ])
  return {
    principals: principals.principals || [],
    principalRoles: principalRoles.roles || [],
  }
})

const configResource = useResource(async () => {
  const prefix = session.state.selectedCatalog
  return catalogApi.config(prefix || undefined)
})

const loading = computed(() => directory.loading.value || session.state.loadingCatalogs)
const error = computed(() => directory.error.value)
const configData = computed(() => configResource.data.value)

const stats = computed(() => {
  const meta = session.state.meta
  return [
    {
      label: '目录（Catalog）',
      value: formatNumber(session.state.catalogs.length),
      foot: `默认 prefix：${meta?.service?.defaultPrefix || '—'}`,
    },
    {
      label: '主体（Principal）',
      value: directory.data.value ? formatNumber(directory.data.value.principals.length) : '—',
      foot: '管理 API /principals',
    },
    {
      label: '服务角色（Principal Role）',
      value: directory.data.value ? formatNumber(directory.data.value.principalRoles.length) : '—',
      foot: '管理 API /principal-roles',
    },
    {
      label: '接入的存储',
      value: meta?.enums?.supportedStorageTypes?.length ?? '—',
      foot: `file-io.type = ${meta?.service?.fileIoType || '—'}`,
    },
  ]
})

const serviceRows = computed(() => {
  const meta = session.state.meta
  if (!meta) return []
  const service = meta.service || {}
  return [
    {
      label: '鉴权（paimon.rest.auth.enabled）',
      tag: service.authEnabled ? '要求令牌' : '关闭',
      tagType: service.authEnabled ? 'success' : 'info',
    },
    {
      // 控制台门禁与数据面鉴权是两件事，分开列：只开着门禁时数据接口仍然匿名可调，
      // 把两者合成一行会让人以为「要求令牌」那一行覆盖了控制台
      label: '控制台门禁（console.required）',
      tag: auth.state.consoleRequired
        ? (auth.gateOnly() ? '要求登录（只挡界面）' : '要求登录')
        : '关闭',
      tagType: auth.state.consoleRequired ? 'success' : 'info',
    },
    {
      label: '授权（paimon.rest.authorization.enabled）',
      tag: service.authorizationEnabled ? '启用 RBAC' : '关闭',
      tagType: service.authorizationEnabled ? 'success' : 'info',
    },
    { label: '凭据下发（credential-manager.type）', value: service.credentialManagerType || '—' },
    { label: '存储实现（file-io.type）', value: service.fileIoType || '—' },
    { label: '自动登记 catalog（auto-create-catalog）', value: String(service.autoCreateCatalog ?? '—') },
    { label: '默认仓库（default-warehouse）', value: service.defaultWarehouse || '—' },
    { label: '路径模板（path-template）', value: service.pathTemplate || '—' },
    {
      label: '分页上限（max-page-size）',
      value: service.maxPageSize === null || service.maxPageSize === undefined
        ? '—'
        : String(service.maxPageSize),
    },
    { label: '服务端版本', value: service.version || '—' },
  ]
})

const checks = computed(() => {
  const meta = session.state.meta
  return [
    {
      label: '控制台元数据',
      path: 'GET /api/console/v1/meta',
      state: session.state.metaError
        ? { type: 'danger', text: '失败' }
        : (meta ? { type: 'success', text: '正常' } : { type: 'info', text: '检测中' }),
      detail: session.state.metaError || '',
    },
    {
      label: '管理 API',
      path: 'GET /api/management/v1/catalogs',
      state: session.state.catalogsError
        ? { type: 'danger', text: '失败' }
        : (session.state.catalogsLoaded ? { type: 'success', text: '正常' } : { type: 'info', text: '检测中' }),
      detail: session.state.catalogsError || '',
    },
    {
      label: 'catalog API',
      path: 'GET /v1/config',
      state: configResource.error.value
        ? { type: 'danger', text: '失败' }
        : (configData.value ? { type: 'success', text: '正常' } : { type: 'info', text: '检测中' }),
      detail: configResource.error.value || '',
    },
    {
      label: '访问令牌',
      path: getBaseUrl() || '同源',
      state: credentials.configured
        ? { type: 'success', text: '已配置' }
        : { type: 'info', text: '未配置' },
      detail: credentials.configured ? '' : '服务端开启鉴权时，所有请求都会返回 401',
    },
  ]
})

function reloadAll() {
  session.loadCatalogs().catch(() => {})
  session.loadMeta()
  directory.load()
  configResource.load()
}

onConsoleRefresh(reloadAll)
</script>

<style scoped>

.pc-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(320px, 1fr);
  gap: 14px;
  margin-top: 14px;
}

.pc-check {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.pc-check-tag {
  flex-shrink: 0;
  margin-top: 1px;
}

.pc-check-label {
  font-size: 12.5px;
  font-weight: 600;
}

.pc-check-detail {
  font-size: 12.5px;
  line-height: 1.6;
  margin-top: 2px;
}

.pc-config {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  padding: 14px 16px;
}

.pc-config-label {
  font-size: 12.5px;
  color: var(--pc-text-dim);
  margin-bottom: 6px;
}

@media (max-width: 1080px) {
  .pc-grid,
  .pc-config {
    grid-template-columns: 1fr;
  }
}
</style>
