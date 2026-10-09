<template>
  <div class="pc-page">
    <PageHeader
      title="Catalog 管理"
      subtitle="管理 API 的 /catalogs 端点：登记仓库位置与存储配置。catalog 的 name 同时是 catalog API 的 prefix，改名前请确认没有客户端在用它。"
    >
      <template #actions>
        <el-button size="small" :loading="table.loading.value" @click="table.load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建 Catalog</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <ResourceState
        :loading="table.loading.value"
        :error="table.error.value"
        :empty="!rows.length"
        empty-text="服务端还没有登记任何 catalog"
      >
        <el-table v-loading="table.loading.value" :data="rows" size="small">
          <el-table-column label="名称" min-width="150">
            <template #default="{ row }">
              <RouterLink class="pc-link" :to="{ name: 'browse', query: { catalog: row.name } }">
                {{ row.name }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column label="存储类型" width="104">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ row.storageConfigInfo?.storageType || '—' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="仓库位置" min-width="230">
            <template #default="{ row }">
              <span class="pc-mono pc-break">{{ warehouseOf(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="属性" min-width="160">
            <template #default="{ row }">
              <span class="pc-mono pc-dim pc-break">{{ inlineMap(row.properties) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="160">
            <template #default="{ row }">
              <TimeText :millis="row.lastUpdateTimestamp" mode="relative" />
            </template>
          </el-table-column>
          <el-table-column label="版本" width="70" align="right">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.entityVersion ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <IconAction icon="Edit" label="编辑" type="primary" @click="openEdit(row)" />
              <IconAction
                icon="Avatar"
                label="角色"
                @click="$router.push({ name: 'catalogRoles', query: { catalog: row.name } })"
              />
              <IconAction icon="Delete" label="删除" type="danger" @click="confirmDelete(row)" />
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </PanelCard>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialog.open"
      :title="dialog.mode === 'create' ? '新建 Catalog' : `编辑 Catalog：${dialog.name}`"
      width="620px"
      :close-on-click-modal="false"
      top="6vh"
      destroy-on-close
    >
      <el-form label-position="top" size="small">
        <el-form-item v-if="dialog.mode === 'create'" required>
          <template #label>
            <FieldLabel label="名称（name / prefix）" tip="创建后不可改名，改名等同于删除重建。" />
          </template>
          <el-input v-model="dialog.name" placeholder="例如 paimon，之后所有 /v1/{prefix}/... 都用它" />
        </el-form-item>

        <el-form-item v-if="dialog.mode === 'create'" required>
          <template #label>
            <FieldLabel
              label="类型（type）"
              tip="INTERNAL 由本服务管理元数据；EXTERNAL 只作为已有仓库的入口。"
            />
          </template>
          <el-select v-model="dialog.type" class="pc-full">
            <el-option v-for="type in catalogTypes" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="dialog.mode === 'edit'">
          <template #label>
            <FieldLabel
              label="当前版本（currentEntityVersion）"
              tip="并发保护：版本与服务端不一致时更新会被拒绝（409），此时请刷新后重试。"
            />
          </template>
          <el-input :model-value="String(dialog.entityVersion)" disabled />
        </el-form-item>
      </el-form>

      <el-alert
        v-if="!supportedTypes.length"
        type="warning"
        :closable="false"
        show-icon
        class="pc-dialog-alert"
      >
        <template #title>未能读取服务端支持的存储类型</template>
        <div>
          存储类型的可选范围来自 <code class="pc-mono">GET /api/console/v1/meta</code>。
          当前读不到，表单只能给出保守的默认值（FILE），提交时可能被服务端以
          「本部署未接入该存储实现」拒绝。可先到「连接设置」确认地址与令牌。
        </div>
      </el-alert>

      <el-divider content-position="left">存储配置（storageConfigInfo）</el-divider>
      <!--
        key 让每次打开都拿到一个新实例：本组件的表单是内部副本，
        靠 props 同步（见组件内的说明），这里是第三层保险——极端情况下
        （例如同一份 storageConfigInfo 对象被连续两次传入、引用未变）
        重建实例比依赖 watch 更不容错。
      -->
      <StorageConfigFields
        :key="dialog.mode === 'create' ? 'create' : `edit:${dialog.name}`"
        ref="storageForm"
        v-model="dialog.storageConfigInfo"
        :storage-types="storageTypes"
        :supported-types="supportedTypes"
        :static-credentials-enabled="staticCredentialsEnabled"
      />

      <el-divider content-position="left">目录属性（properties）</el-divider>
      <PropertiesEditor v-model="dialog.properties" hint="例如 owner、warehouse；留空即可" />

      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">
          {{ dialog.mode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import StorageConfigFields from '@/components/StorageConfigFields.vue'
import TimeText from '@/components/TimeText.vue'
import { managementApi } from '@/api/management.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { inlineMap } from '@/utils/format.js'

const table = useResource(() => managementApi.listCatalogs())

const rows = computed(() => table.data.value?.catalogs || [])

const meta = computed(() => session.state.meta)
const catalogTypes = computed(() => meta.value?.enums?.catalogTypes || ['INTERNAL', 'EXTERNAL'])
const storageTypes = computed(() => meta.value?.enums?.storageTypes || ['S3', 'GCS', 'AZURE', 'OBS', 'OSS', 'FILE'])
const supportedTypes = computed(() => meta.value?.enums?.supportedStorageTypes || [])
/**
 * 本部署能否保存静态凭据（服务端配了落库加密密钥）。
 *
 * <p>取不到元数据时按 false 处理：乐观放开的代价是用户填完 AK/SK 再吃一个 400，
 * 而保守禁用的代价只是「暂时不能填」，且表单会说明原因。
 */
const staticCredentialsEnabled = computed(() => meta.value?.enums?.staticCredentialsEnabled === true)

const storageForm = ref(null)

const dialog = reactive({
  open: false,
  mode: 'create',
  saving: false,
  name: '',
  type: 'INTERNAL',
  entityVersion: 0,
  properties: {},
  storageConfigInfo: {},
})

function warehouseOf(row) {
  return row.storageConfigInfo?.allowedLocations?.[0] || row.properties?.warehouse || '—'
}

function openCreate() {
  dialog.mode = 'create'
  dialog.name = ''
  dialog.type = 'INTERNAL'
  dialog.entityVersion = 0
  dialog.properties = {}
  dialog.storageConfigInfo = { storageType: supportedTypes.value[0] || 'FILE', allowedLocations: [] }
  dialog.open = true
}

function openEdit(row) {
  dialog.mode = 'edit'
  dialog.name = row.name
  dialog.type = row.type
  dialog.entityVersion = row.entityVersion ?? 0
  dialog.properties = { ...(row.properties || {}) }
  dialog.storageConfigInfo = row.storageConfigInfo
    ? JSON.parse(JSON.stringify(row.storageConfigInfo))
    : fallbackStorage(row)
  dialog.open = true
}

/**
 * 服务端没有返回 `storageConfigInfo` 时的兜底（早期版本登记的 catalog）。
 *
 * <p>不能直接给一个空对象：表单会在 `allowedLocations` 为空的校验上卡住，
 * 用户被迫手填一个本来就已知的位置。仓库位置通常就在 `properties.warehouse` 里，
 * 把它回填成首个允许位置——**回填而不是改写**，两者语义不同，
 * 用户看到值并确认，比表单凭空替他决定要好。
 */
function fallbackStorage(row) {
  const warehouse = row.properties?.warehouse
  return {
    storageType: supportedTypes.value[0] || 'FILE',
    allowedLocations: warehouse ? [warehouse] : [],
  }
}

async function save() {
  if (dialog.mode === 'create' && !dialog.name.trim()) {
    notifyError(new Error('请填写 catalog 名称'))
    return
  }
  const invalid = storageForm.value?.validate()
  if (invalid) {
    notifyError(new Error(invalid))
    return
  }
  const storageConfigInfo = storageForm.value?.build() ?? dialog.storageConfigInfo

  dialog.saving = true
  try {
    if (dialog.mode === 'create') {
      await managementApi.createCatalog({
        type: dialog.type,
        name: dialog.name.trim(),
        properties: dialog.properties,
        storageConfigInfo,
      })
      notifySuccess(`已创建 catalog ${dialog.name.trim()}`)
    } else {
      await managementApi.updateCatalog(dialog.name, {
        currentEntityVersion: dialog.entityVersion,
        properties: dialog.properties,
        storageConfigInfo,
      })
      notifySuccess(`已更新 catalog ${dialog.name}`)
    }
    dialog.open = false
    await table.load()
    await session.loadCatalogs()
  } catch (error) {
    notifyError(error)
  } finally {
    dialog.saving = false
  }
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除 Catalog',
    target: `确认删除 catalog「${row.name}」？`,
    detail: '服务端会一并删除这个 catalog 下的角色与授权关系。仓库里的数据文件不受影响，'
      + '但如果仍有客户端在用这个 prefix，它们的请求会开始失败。',
  })
  if (!ok) return
  try {
    await managementApi.deleteCatalog(row.name)
    notifySuccess(`已删除 catalog ${row.name}`)
    await table.load()
    await session.loadCatalogs()
  } catch (error) {
    notifyError(error)
  }
}

onConsoleRefresh(() => table.load())
</script>

<style scoped>
.pc-dialog-alert {
  margin-bottom: 14px;
}

:deep(.el-divider__text) {
  font-size: 13px;
  color: var(--pc-text-dim);
  background: var(--pc-surface);
}
</style>
