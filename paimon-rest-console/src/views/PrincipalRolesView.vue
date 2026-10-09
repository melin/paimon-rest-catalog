<template>
  <div class="pc-page">
    <PageHeader
      title="服务角色（Principal Role）"
      subtitle="服务角色是主体与 catalog 角色之间的中间层：主体 —(持有)→ 服务角色 —(在某 catalog 上持有)→ catalog 角色 —(携带)→ 授权。角色本身的管理需要服务管理员身份。"
    >
      <template #actions>
        <el-button size="small" :loading="table.loading.value" @click="table.load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建服务角色</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <ResourceState
        :loading="table.loading.value"
        :error="table.error.value"
        :empty="!rows.length"
        empty-text="还没有服务角色"
      >
        <el-table v-loading="table.loading.value" :data="rows" size="small">
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column label="联邦（federated）" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.federated ? 'warning' : 'info'">
                {{ row.federated ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="属性" min-width="170">
            <template #default="{ row }">
              <span class="pc-mono pc-dim pc-break">{{ inlineMap(row.properties) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }"><TimeText :millis="row.createTimestamp" /></template>
          </el-table-column>
          <el-table-column label="版本" width="70" align="right">
            <template #default="{ row }"><span class="pc-mono">{{ row.entityVersion ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openDetail(row)">成员与角色</el-button>
              <el-button size="small" text @click="openEdit(row)">编辑</el-button>
              <el-button size="small" text type="danger" @click="confirmDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </PanelCard>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialog.open"
      :title="dialog.mode === 'create' ? '新建服务角色' : `编辑服务角色：${dialog.name}`"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top" size="small">
        <el-form-item v-if="dialog.mode === 'create'" label="名称" required>
          <el-input v-model="dialog.name" placeholder="例如 service_admin、data_reader" />
        </el-form-item>
        <el-form-item v-if="dialog.mode === 'create'">
          <template #label>
            <FieldLabel
              label="联邦角色（federated）"
              tip="联邦角色表示由外部身份提供方映射而来，不由本服务管理成员。"
            />
          </template>
          <el-switch v-model="dialog.federated" />
        </el-form-item>
        <el-form-item v-else label="当前版本（currentEntityVersion）">
          <el-input :model-value="String(dialog.entityVersion)" disabled />
        </el-form-item>
      </el-form>
      <el-divider content-position="left">属性（properties）</el-divider>
      <PropertiesEditor v-model="dialog.properties" hint="自由扩展字段，留空即可" />
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">
          {{ dialog.mode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 成员与 catalog 角色 -->
    <el-drawer v-model="detail.open" :title="`服务角色：${detail.name}`" size="620px">
      <el-tabs v-model="detail.tab">
        <el-tab-pane label="成员主体" name="principals">
          <ResourceState
            :loading="detail.loadingPrincipals"
            :error="detail.errorPrincipals"
            :empty="!detail.principals.length"
            empty-text="还没有主体持有这个服务角色"
          >
            <el-table :data="detail.principals" size="small">
              <el-table-column prop="name" label="主体" min-width="160" />
              <el-table-column label="Client ID" min-width="180">
                <template #default="{ row }"><CopyText :text="row.clientId" /></template>
              </el-table-column>
            </el-table>
          </ResourceState>
          <p class="pc-dim pc-drawer-note">
            授予主体需要到「主体」页面操作：那条接口按主体定位，这里只做展示。
          </p>
        </el-tab-pane>

        <el-tab-pane label="Catalog 角色" name="catalogRoles">
          <div class="pc-role-add">
            <el-select v-model="detail.catalogName" size="small" placeholder="选择 catalog" filterable class="pc-role-select">
              <el-option
                v-for="catalog in session.state.catalogs"
                :key="catalog.name"
                :label="catalog.name"
                :value="catalog.name"
              />
            </el-select>
            <el-button size="small" :loading="detail.loadingCatalogRoles" :disabled="!detail.catalogName" @click="loadCatalogRoles">
              加载
            </el-button>
          </div>

          <div v-if="detail.catalogName" class="pc-role-add">
            <el-select v-model="detail.toGrant" size="small" placeholder="选择要授予的 catalog 角色" filterable class="pc-role-select">
              <el-option
                v-for="role in grantableCatalogRoles"
                :key="role.name"
                :label="role.name"
                :value="role.name"
              />
            </el-select>
            <el-button size="small" type="primary" :loading="detail.saving" :disabled="!detail.toGrant" @click="grantCatalogRole">
              授予
            </el-button>
          </div>

          <el-alert v-if="detail.errorCatalogRoles" type="error" :closable="false" show-icon class="pc-hint">
            <template #title>{{ detail.errorCatalogRoles }}</template>
            <div>授予 catalog 角色需要在目标 catalog 上具备 CATALOG_MANAGE_ACCESS。</div>
          </el-alert>

          <el-table v-else :data="detail.catalogRoles" size="small" empty-text="这个服务角色在该 catalog 上还没有 catalog 角色">
            <el-table-column prop="name" label="Catalog 角色" min-width="180" />
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }"><TimeText :millis="row.createTimestamp" /></template>
            </el-table-column>
            <el-table-column label="" width="90" align="right">
              <template #default="{ row }">
                <el-button size="small" text type="danger" @click="revokeCatalogRole(row.name)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, reactive } from 'vue'

import CopyText from '@/components/CopyText.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import TimeText from '@/components/TimeText.vue'
import { managementApi } from '@/api/management.js'
import { describeError } from '@/api/client.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { inlineMap } from '@/utils/format.js'

const table = useResource(() => managementApi.listPrincipalRoles())
const rows = computed(() => table.data.value?.roles || [])

const dialog = reactive({
  open: false,
  mode: 'create',
  name: '',
  federated: false,
  entityVersion: 0,
  properties: {},
  saving: false,
})

function openCreate() {
  Object.assign(dialog, { open: true, mode: 'create', name: '', federated: false, properties: {} })
}

function openEdit(row) {
  Object.assign(dialog, {
    open: true,
    mode: 'edit',
    name: row.name,
    entityVersion: row.entityVersion ?? 0,
    properties: { ...(row.properties || {}) },
  })
}

async function save() {
  if (dialog.mode === 'create' && !dialog.name.trim()) {
    notifyError(new Error('请填写服务角色名称'))
    return
  }
  dialog.saving = true
  try {
    if (dialog.mode === 'create') {
      await managementApi.createPrincipalRole({
        name: dialog.name.trim(),
        federated: dialog.federated,
        properties: dialog.properties,
      })
      notifySuccess(`已创建服务角色 ${dialog.name.trim()}`)
    } else {
      await managementApi.updatePrincipalRole(dialog.name, {
        currentEntityVersion: dialog.entityVersion,
        properties: dialog.properties,
      })
      notifySuccess(`已更新服务角色 ${dialog.name}`)
    }
    dialog.open = false
    table.load()
  } catch (error) {
    notifyError(error)
  } finally {
    dialog.saving = false
  }
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除服务角色',
    target: `确认删除服务角色「${row.name}」？`,
    detail: '与该角色相关的绑定会一并移除：主体的角色授予、以及它在各 catalog 上持有的 catalog 角色。',
  })
  if (!ok) return
  try {
    await managementApi.deletePrincipalRole(row.name)
    notifySuccess(`已删除服务角色 ${row.name}`)
    table.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 详情

const detail = reactive({
  open: false,
  name: '',
  tab: 'principals',
  principals: [],
  loadingPrincipals: false,
  errorPrincipals: '',
  catalogName: '',
  catalogRoles: [],
  allCatalogRoles: [],
  loadingCatalogRoles: false,
  errorCatalogRoles: '',
  toGrant: '',
  saving: false,
})

const grantableCatalogRoles = computed(() => {
  const owned = new Set(detail.catalogRoles.map((role) => role.name))
  return detail.allCatalogRoles.filter((role) => !owned.has(role.name))
})

async function openDetail(row) {
  detail.name = row.name
  detail.tab = 'principals'
  detail.catalogName = session.state.selectedCatalog || ''
  detail.catalogRoles = []
  detail.allCatalogRoles = []
  detail.toGrant = ''
  detail.open = true

  detail.loadingPrincipals = true
  detail.errorPrincipals = ''
  try {
    const payload = await managementApi.listPrincipalsOfPrincipalRole(row.name)
    detail.principals = payload.principals || []
  } catch (error) {
    detail.errorPrincipals = describeError(error)
  } finally {
    detail.loadingPrincipals = false
  }

  if (detail.catalogName) {
    await loadCatalogRoles()
  }
}

async function loadCatalogRoles() {
  detail.loadingCatalogRoles = true
  detail.errorCatalogRoles = ''
  try {
    const [owned, all] = await Promise.all([
      managementApi.listCatalogRolesOfPrincipalRole(detail.name, detail.catalogName),
      managementApi.listCatalogRoles(detail.catalogName),
    ])
    detail.catalogRoles = owned.roles || []
    detail.allCatalogRoles = all.roles || []
  } catch (error) {
    detail.catalogRoles = []
    detail.allCatalogRoles = []
    detail.errorCatalogRoles = describeError(error)
  } finally {
    detail.loadingCatalogRoles = false
  }
}

async function grantCatalogRole() {
  detail.saving = true
  try {
    await managementApi.grantCatalogRole(detail.name, detail.catalogName, { name: detail.toGrant })
    notifySuccess(`已在 ${detail.catalogName} 上授予 ${detail.toGrant}`)
    detail.toGrant = ''
    await loadCatalogRoles()
  } catch (error) {
    notifyError(error)
  } finally {
    detail.saving = false
  }
}

async function revokeCatalogRole(name) {
  const ok = await confirmDanger({
    title: '移除 Catalog 角色',
    target: `在 catalog「${detail.catalogName}」上移除角色「${name}」？`,
    detail: '持有这个服务角色的所有主体都会失去该 catalog 角色带来的权限。',
    confirmText: '移除',
  })
  if (!ok) return
  try {
    await managementApi.revokeCatalogRole(detail.name, detail.catalogName, name)
    notifySuccess(`已移除 ${name}`)
    await loadCatalogRoles()
  } catch (error) {
    notifyError(error)
  }
}

onConsoleRefresh(() => table.load())
</script>

<style scoped>

.pc-role-add {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.pc-role-select {
  flex: 1;
}

.pc-drawer-note {
  margin-top: 14px;
  font-size: 13px;
  line-height: 1.7;
}

:deep(.el-divider__text) {
  font-size: 13px;
  color: var(--pc-text-dim);
  background: var(--pc-surface);
}
</style>
