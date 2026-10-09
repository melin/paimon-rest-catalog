<template>
  <div class="pc-page">
    <PageHeader
      title="Catalog 角色与授权"
      subtitle="catalog 角色是权限的载体，授权把「某项权限」绑定到「某类资源」上。增删授权的动词与服务端一致：新增用 PUT，撤销用 POST。整组操作需要在目标 catalog 上具备 CATALOG_MANAGE_ACCESS。"
    >
      <template #actions>
        <el-select v-model="catalogName" size="small" placeholder="选择 catalog" class="pc-catalog" @change="onCatalogChange">
          <el-option
            v-for="catalog in session.state.catalogs"
            :key="catalog.name"
            :label="catalog.name"
            :value="catalog.name"
          />
        </el-select>
        <el-button size="small" :disabled="!catalogName" :loading="table.loading.value" @click="table.load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" :disabled="!catalogName" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建 Catalog 角色</span>
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="!catalogName" type="warning" :closable="false" show-icon>
      <template #title>请先选择一个 catalog</template>
      <div>catalog 角色隶属于某个 catalog，所有接口路径都带上 catalog 名。</div>
    </el-alert>

    <template v-else>
      <PanelCard flush>
        <ResourceState
          :loading="table.loading.value"
          :error="table.error.value"
          :empty="!rows.length"
          empty-text="这个 catalog 下还没有 catalog 角色"
        >
          <el-table v-loading="table.loading.value" :data="rows" size="small">
            <el-table-column prop="name" label="角色名" min-width="160" />
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
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <IconAction icon="Key" label="授权" type="primary" @click="openGrants(row)" />
                <IconAction icon="User" label="持有者" @click="openHolders(row)" />
                <IconAction icon="Edit" label="编辑" @click="openEdit(row)" />
                <IconAction icon="Delete" label="删除" type="danger" @click="confirmDelete(row)" />
              </template>
            </el-table-column>
          </el-table>
        </ResourceState>
      </PanelCard>
    </template>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialog.open"
      :title="dialog.mode === 'create' ? '新建 Catalog 角色' : `编辑 Catalog 角色：${dialog.name}`"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top" size="small">
        <el-form-item v-if="dialog.mode === 'create'" label="角色名" required>
          <el-input v-model="dialog.name" placeholder="例如 catalog_admin、analyst" />
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

    <!-- 授权 -->
    <el-drawer v-model="grantDrawer.open" :title="`授权：${grantDrawer.role} @ ${catalogName}`" size="720px">
      <el-alert type="info" :closable="false" show-icon class="pc-hint">
        <template #title>资源类型决定可授予的权限</template>
        <div>
          权限取值按资源层级分组（对应管理规格里的 6 个权限 enum），
          提交不属于该层级的权限会被服务端拒绝。
        </div>
      </el-alert>

      <div class="pc-grant-form">
        <el-select v-model="grantForm.type" size="small" class="pc-grant-type" @change="onGrantTypeChange">
          <el-option v-for="type in grantTypes" :key="type" :label="type" :value="type" />
        </el-select>

        <!-- el-select-free-input：namespace 是可以逐级填写的任意路径，没有候选列表可给，
             这里靠 allow-create 直接输入。属于有意的自由输入，不是漏写 el-option -->
        <el-select
          v-if="grantForm.type !== 'catalog'"
          v-model="grantForm.namespace"
          size="small"
          multiple
          filterable
          allow-create
          default-first-option
          class="pc-grant-ns"
          placeholder="命名空间路径（逐级填写，如 default）"
        />

        <el-input
          v-if="objectField"
          v-model="grantForm.objectName"
          size="small"
          class="pc-grant-obj"
          :placeholder="`${objectField}（对象名）`"
        />

        <el-select v-model="grantForm.privilege" size="small" filterable class="pc-grant-priv" placeholder="权限">
          <el-option v-for="privilege in privileges" :key="privilege" :label="privilege" :value="privilege" />
        </el-select>

        <el-button size="small" type="primary" :loading="grantDrawer.saving" @click="addGrant">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新增授权</span>
        </el-button>
      </div>

      <div class="pc-grant-preview">
        <span class="pc-dim">将提交的 grant：</span>
        <code class="pc-mono">{{ JSON.stringify(previewGrant) }}</code>
      </div>

      <ResourceState
        :loading="grantDrawer.loading"
        :error="grantDrawer.error"
        :empty="!grantDrawer.grants.length"
        empty-text="这个角色还没有任何授权"
      >
        <el-table :data="grantDrawer.grants" size="small">
          <el-table-column label="资源" min-width="240">
            <template #default="{ row }">
              <div class="pc-grant-cell">
                <el-tag size="small" effect="plain">{{ row.type }}</el-tag>
                <span class="pc-mono">{{ resourceText(row) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="权限" min-width="210">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.privilege }}</span>
            </template>
          </el-table-column>
          <el-table-column label="" width="70" align="right">
            <template #default="{ row }">
              <IconAction icon="Remove" label="撤销" type="danger" @click="revokeGrant(row)" />
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </el-drawer>

    <!-- 持有者 -->
    <el-drawer v-model="holderDrawer.open" :title="`持有者：${holderDrawer.role} @ ${catalogName}`" size="520px">
      <ResourceState
        :loading="holderDrawer.loading"
        :error="holderDrawer.error"
        :empty="!holderDrawer.roles.length"
        empty-text="没有服务角色持有这个 catalog 角色"
      >
        <el-table :data="holderDrawer.roles" size="small">
          <el-table-column prop="name" label="服务角色" min-width="180" />
          <el-table-column label="联邦" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.federated ? 'warning' : 'info'">
                {{ row.federated ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
      <p class="pc-dim pc-drawer-note">
        授予与撤销在「服务角色」页面操作：那条接口按 principal role 定位。
      </p>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import IconAction from '@/components/IconAction.vue'
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

const route = useRoute()
const router = useRouter()

const catalogName = ref(route.query.catalog || session.state.selectedCatalog || '')

const table = useResource(() => managementApi.listCatalogRoles(catalogName.value), { immediate: false })

const rows = computed(() => table.data.value?.roles || [])

const meta = computed(() => session.state.meta)
const grantTypes = computed(
  () => meta.value?.enums?.grantTypes || ['catalog', 'namespace', 'table', 'view', 'policy', 'semantic-model'],
)

/** 规格里各资源类型的对象名字段名，与服务端 `GrantDtos.objectFieldName` 一致。 */
const OBJECT_FIELDS = {
  table: 'tableName',
  view: 'viewName',
  policy: 'policyName',
  'semantic-model': 'semanticModelName',
}

const objectField = computed(() => OBJECT_FIELDS[grantForm.type] || '')

const privileges = computed(() => {
  const map = meta.value?.enums?.privilegesByGrantType || {}
  return map[grantForm.type] || []
})

function onCatalogChange(name) {
  session.selectCatalog(name)
  router.replace({ name: 'catalogRoles', query: { catalog: name } })
  table.load()
}

watch(catalogName, (name, previous) => {
  if (name && name !== previous) {
    table.load()
  }
}, { immediate: true })

// ------------------------------------------------------------------ 角色 CRUD

const dialog = reactive({
  open: false,
  mode: 'create',
  name: '',
  entityVersion: 0,
  properties: {},
  saving: false,
})

function openCreate() {
  Object.assign(dialog, { open: true, mode: 'create', name: '', properties: {} })
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
    notifyError(new Error('请填写角色名'))
    return
  }
  dialog.saving = true
  try {
    if (dialog.mode === 'create') {
      await managementApi.createCatalogRole(catalogName.value, {
        name: dialog.name.trim(),
        properties: dialog.properties,
      })
      notifySuccess(`已创建角色 ${dialog.name.trim()}`)
    } else {
      await managementApi.updateCatalogRole(catalogName.value, dialog.name, {
        currentEntityVersion: dialog.entityVersion,
        properties: dialog.properties,
      })
      notifySuccess(`已更新角色 ${dialog.name}`)
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
    title: '删除 Catalog 角色',
    target: `确认删除角色「${row.name}」？`,
    detail: '角色携带的全部授权会一并失效，持有它的服务角色将立即失去这些权限。'
      + '服务端会拒绝删除仍被引用的角色——若失败，请先在所有服务角色上移除它。',
  })
  if (!ok) return
  try {
    await managementApi.deleteCatalogRole(catalogName.value, row.name)
    notifySuccess(`已删除角色 ${row.name}`)
    table.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 授权

const grantForm = reactive({
  type: 'catalog',
  namespace: [],
  objectName: '',
  privilege: '',
})

const grantDrawer = reactive({
  open: false,
  role: '',
  grants: [],
  loading: false,
  error: '',
  saving: false,
})

/** 按规格形状拼出待提交的 grant，同时也用它渲染预览文本。 */
const previewGrant = computed(() => {
  const grant = { type: grantForm.type }
  if (grantForm.type !== 'catalog') {
    grant.namespace = [...grantForm.namespace]
  }
  if (objectField.value) {
    grant[objectField.value] = grantForm.objectName
  }
  grant.privilege = grantForm.privilege
  return grant
})

function resourceText(grant) {
  const parts = []
  if (grant.namespace?.length) parts.push(grant.namespace.join('.'))
  for (const key of Object.values(OBJECT_FIELDS)) {
    if (grant[key]) parts.push(grant[key])
  }
  return parts.length ? parts.join(' / ') : '（整个 catalog）'
}

function onGrantTypeChange() {
  grantForm.namespace = []
  grantForm.objectName = ''
  grantForm.privilege = ''
}

async function loadGrants() {
  grantDrawer.loading = true
  grantDrawer.error = ''
  try {
    const payload = await managementApi.listGrants(catalogName.value, grantDrawer.role)
    grantDrawer.grants = payload.grants || []
  } catch (error) {
    grantDrawer.error = describeError(error)
  } finally {
    grantDrawer.loading = false
  }
}

async function openGrants(row) {
  grantDrawer.role = row.name
  grantDrawer.open = true
  onGrantTypeChange()
  await loadGrants()
}

async function addGrant() {
  if (grantForm.type !== 'catalog' && !grantForm.namespace.length) {
    notifyError(new Error('请填写命名空间'))
    return
  }
  if (objectField.value && !grantForm.objectName.trim()) {
    notifyError(new Error(`请填写 ${objectField.value}`))
    return
  }
  if (!grantForm.privilege) {
    notifyError(new Error('请选择权限'))
    return
  }
  grantDrawer.saving = true
  try {
    await managementApi.addGrant(catalogName.value, grantDrawer.role, previewGrant.value)
    notifySuccess('已新增授权')
    grantForm.privilege = ''
    await loadGrants()
  } catch (error) {
    notifyError(error)
  } finally {
    grantDrawer.saving = false
  }
}

async function revokeGrant(grant) {
  const ok = await confirmDanger({
    title: '撤销授权',
    target: `撤销 ${grant.privilege} on ${grant.type}？`,
    detail: `资源：${resourceText(grant)}\n角色：${grantDrawer.role}`,
    confirmText: '撤销',
  })
  if (!ok) return
  try {
    // 撤销用规格声明的同一形状；只回传规格认识的字段，避免把服务端附加的其他键带回去
    const shape = { type: grant.type }
    if (grant.namespace?.length) shape.namespace = grant.namespace
    for (const key of Object.values(OBJECT_FIELDS)) {
      if (grant[key]) shape[key] = grant[key]
    }
    shape.privilege = grant.privilege
    await managementApi.revokeGrant(catalogName.value, grantDrawer.role, shape)
    notifySuccess('已撤销授权')
    await loadGrants()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 持有者

const holderDrawer = reactive({ open: false, role: '', roles: [], loading: false, error: '' })

async function openHolders(row) {
  holderDrawer.role = row.name
  holderDrawer.open = true
  holderDrawer.loading = true
  holderDrawer.error = ''
  try {
    const payload = await managementApi.listPrincipalRolesOfCatalogRole(catalogName.value, row.name)
    // 响应是 PrincipalRoles 形状：这里展示的是「哪些服务角色持有它」
    holderDrawer.roles = payload.roles || payload.principalRoles || []
  } catch (error) {
    holderDrawer.error = describeError(error)
  } finally {
    holderDrawer.loading = false
  }
}

onConsoleRefresh(() => {
  if (catalogName.value) {
    table.load()
  }
})
</script>

<style scoped>
.pc-catalog {
  width: 170px;
}

.pc-grant-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.pc-grant-type {
  width: 150px;
}

.pc-grant-ns {
  flex: 1;
  min-width: 180px;
}

.pc-grant-obj {
  width: 170px;
}

.pc-grant-priv {
  width: 250px;
}

.pc-grant-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  font-size: 12.5px;
  flex-wrap: wrap;
}

.pc-grant-preview code {
  padding: 2px 6px;
  background: var(--pc-surface-2);
  border: 1px solid var(--pc-border);
  border-radius: 4px;
}

.pc-grant-cell {
  display: flex;
  align-items: center;
  gap: 8px;
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
