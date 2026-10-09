<template>
  <div class="pc-page">
    <PageHeader
      title="主体（Principal）"
      subtitle="主体是访问凭证的持有者。管理 API 把整组主体操作归给服务管理员——规格的权限枚举里没有覆盖「管理主体」的层级，因此这里不看 catalog 角色的授权。"
    >
      <template #actions>
        <el-button size="small" :loading="table.loading.value" @click="table.load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建主体</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <ResourceState
        :loading="table.loading.value"
        :error="table.error.value"
        :empty="!rows.length"
        empty-text="还没有主体"
      >
        <el-table v-loading="table.loading.value" :data="rows" size="small">
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column label="Client ID" min-width="190">
            <template #default="{ row }">
              <CopyText :text="row.clientId" />
            </template>
          </el-table-column>
          <el-table-column label="属性" min-width="150">
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
              <IconAction icon="Avatar" label="角色" type="primary" @click="openRoles(row)" />
              <IconAction icon="Refresh" label="轮换密钥" @click="rotate(row)" />
              <IconAction icon="Key" label="重置密钥" @click="openReset(row)" />
              <IconAction icon="Delete" label="删除" type="danger" @click="confirmDelete(row)" />
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </PanelCard>

    <!-- 新建主体 -->
    <el-dialog v-model="createDialog.open" title="新建主体" width="520px" :close-on-click-modal="false"
      destroy-on-close>
      <el-alert type="warning" :closable="false" show-icon class="pc-hint">
        <template #title>服务端只保存密钥摘要</template>
        <div>创建成功后，明文密钥只返回一次，请当场保存。</div>
      </el-alert>
      <el-form label-position="top" size="small">
        <el-form-item label="名称" required>
          <el-input v-model="createDialog.name" placeholder="例如 spark-job、flink-prod" />
        </el-form-item>
        <el-form-item>
          <template #label>
            <FieldLabel
              label="要求轮换凭据（credentialRotationRequired）"
              tip="打开后，拿到凭据的客户端需要在首次使用前完成一次轮换。"
            />
          </template>
          <el-switch v-model="createDialog.rotationRequired" />
        </el-form-item>
      </el-form>
      <el-divider content-position="left">属性（properties）</el-divider>
      <PropertiesEditor v-model="createDialog.properties" hint="自由扩展字段，留空即可" />
      <template #footer>
        <el-button @click="createDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="saveCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重置密钥 -->
    <el-dialog v-model="resetDialog.open" :title="`重置密钥：${resetDialog.name}`" width="520px" :close-on-click-modal="false"
      destroy-on-close>
      <p class="pc-dim">两个字段都留空时，由服务端生成一套新的 clientId 与 clientSecret。</p>
      <el-form label-position="top" size="small">
        <el-form-item label="Client ID（可选）">
          <el-input v-model="resetDialog.clientId" />
        </el-form-item>
        <el-form-item label="Client Secret（可选）">
          <el-input v-model="resetDialog.clientSecret" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="resetDialog.saving" @click="saveReset">重置</el-button>
      </template>
    </el-dialog>

    <!-- 角色抽屉 -->
    <el-drawer v-model="rolesDrawer.open" :title="`服务角色：${rolesDrawer.principal}`" size="560px">
      <div class="pc-role-add">
        <el-select v-model="rolesDrawer.toGrant" size="small" placeholder="选择要授予的服务角色" filterable class="pc-role-select">
          <el-option
            v-for="role in grantableRoles"
            :key="role.name"
            :label="role.name"
            :value="role.name"
          />
        </el-select>
        <el-button size="small" type="primary" :loading="rolesDrawer.saving" :disabled="!rolesDrawer.toGrant" @click="grantRole">
          授予
        </el-button>
      </div>

      <ResourceState
        :loading="rolesDrawer.loading"
        :error="rolesDrawer.error"
        :empty="!rolesDrawer.roles.length"
        empty-text="这个主体还没有被授予任何服务角色"
      >
        <el-table :data="rolesDrawer.roles" size="small">
          <el-table-column prop="name" label="角色" min-width="160" />
          <el-table-column label="联邦（federated）" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.federated ? 'warning' : 'info'">
                {{ row.federated ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="" width="70" align="right">
            <template #default="{ row }">
              <IconAction icon="Close" label="移除" type="danger" @click="revokeRole(row.name)" />
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </el-drawer>

    <CredentialDialog
      v-model="credentialDialog.open"
      :principal-name="credentialDialog.principal"
      :credentials="credentialDialog.credentials"
    />
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'

import CopyText from '@/components/CopyText.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import IconAction from '@/components/IconAction.vue'
import CredentialDialog from '@/components/CredentialDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import TimeText from '@/components/TimeText.vue'
import { managementApi } from '@/api/management.js'
import { describeError } from '@/api/client.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh, useResource } from '@/composables/index.js'
import { inlineMap } from '@/utils/format.js'

const table = useResource(() => managementApi.listPrincipals())
const rolesResource = useResource(() => managementApi.listPrincipalRoles(), { immediate: false })

const rows = computed(() => table.data.value?.principals || [])
const allRoles = computed(() => rolesResource.data.value?.roles || [])

const credentialDialog = reactive({ open: false, principal: '', credentials: {} })

// ------------------------------------------------------------------ 新建

const createDialog = reactive({
  open: false,
  name: '',
  rotationRequired: false,
  properties: {},
  saving: false,
})

function openCreate() {
  Object.assign(createDialog, { open: true, name: '', rotationRequired: false, properties: {} })
  rolesResource.load()
}

async function saveCreate() {
  const name = createDialog.name.trim()
  if (!name) {
    notifyError(new Error('请填写主体名称'))
    return
  }
  createDialog.saving = true
  try {
    const payload = await managementApi.createPrincipal(
      { name, properties: createDialog.properties },
      createDialog.rotationRequired,
    )
    createDialog.open = false
    credentialDialog.principal = name
    credentialDialog.credentials = payload.credentials || {}
    credentialDialog.open = true
    table.load()
  } catch (error) {
    notifyError(error)
  } finally {
    createDialog.saving = false
  }
}

// ------------------------------------------------------------------ 密钥

async function rotate(row) {
  const ok = await confirmDanger({
    title: '轮换密钥',
    target: `轮换主体「${row.name}」的凭据？`,
    detail: 'clientId 保持不变，clientSecret 会换成新的。用旧密钥的客户端会立即开始鉴权失败。',
    confirmText: '轮换',
  })
  if (!ok) return
  try {
    const payload = await managementApi.rotatePrincipal(row.name)
    credentialDialog.principal = row.name
    credentialDialog.credentials = payload.credentials || {}
    credentialDialog.open = true
    table.load()
  } catch (error) {
    notifyError(error)
  }
}

const resetDialog = reactive({ open: false, name: '', clientId: '', clientSecret: '', saving: false })

function openReset(row) {
  Object.assign(resetDialog, { open: true, name: row.name, clientId: '', clientSecret: '' })
}

async function saveReset() {
  resetDialog.saving = true
  try {
    const body = {}
    if (resetDialog.clientId.trim()) body.clientId = resetDialog.clientId.trim()
    if (resetDialog.clientSecret.trim()) body.clientSecret = resetDialog.clientSecret.trim()
    const payload = await managementApi.resetPrincipal(resetDialog.name, body)
    resetDialog.open = false
    credentialDialog.principal = resetDialog.name
    credentialDialog.credentials = payload.credentials || {}
    credentialDialog.open = true
    table.load()
  } catch (error) {
    notifyError(error)
  } finally {
    resetDialog.saving = false
  }
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除主体',
    target: `确认删除主体「${row.name}」？`,
    detail: '该主体持有的服务角色绑定会一并移除。已经用它的凭据签发的令牌在过期前仍然有效。',
  })
  if (!ok) return
  try {
    await managementApi.deletePrincipal(row.name)
    notifySuccess(`已删除主体 ${row.name}`)
    table.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 角色绑定

const rolesDrawer = reactive({
  open: false,
  principal: '',
  roles: [],
  loading: false,
  error: '',
  toGrant: '',
  saving: false,
})

const grantableRoles = computed(() => {
  const owned = new Set(rolesDrawer.roles.map((role) => role.name))
  return allRoles.value.filter((role) => !owned.has(role.name))
})

async function loadRoles() {
  rolesDrawer.loading = true
  rolesDrawer.error = ''
  try {
    const payload = await managementApi.listPrincipalRolesOfPrincipal(rolesDrawer.principal)
    rolesDrawer.roles = payload.roles || []
  } catch (error) {
    rolesDrawer.error = describeError(error)
  } finally {
    rolesDrawer.loading = false
  }
}

async function openRoles(row) {
  rolesDrawer.principal = row.name
  rolesDrawer.toGrant = ''
  rolesDrawer.open = true
  rolesResource.load()
  await loadRoles()
}

async function grantRole() {
  rolesDrawer.saving = true
  try {
    await managementApi.grantPrincipalRole(rolesDrawer.principal, { name: rolesDrawer.toGrant })
    notifySuccess(`已授予 ${rolesDrawer.toGrant}`)
    rolesDrawer.toGrant = ''
    await loadRoles()
  } catch (error) {
    notifyError(error)
  } finally {
    rolesDrawer.saving = false
  }
}

async function revokeRole(name) {
  const ok = await confirmDanger({
    title: '移除服务角色',
    target: `从主体「${rolesDrawer.principal}」移除角色「${name}」？`,
    detail: '该角色所带的 catalog 权限会立即失效。',
    confirmText: '移除',
  })
  if (!ok) return
  try {
    await managementApi.revokePrincipalRole(rolesDrawer.principal, name)
    notifySuccess(`已移除 ${name}`)
    await loadRoles()
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
  margin-bottom: 14px;
}

.pc-role-select {
  flex: 1;
}

:deep(.el-divider__text) {
  font-size: 13px;
  color: var(--pc-text-dim);
  background: var(--pc-surface);
}
</style>
