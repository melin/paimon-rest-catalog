<template>
  <div class="pc-page">
    <PageHeader
      :title="`表：${table}`"
      :subtitle="`${prefix} / ${database} / ${table}　路径与元数据来自 catalog API，与引擎客户端读到的完全一致。`"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'browse', query: { catalog: prefix, database } })">
          <el-icon><Back /></el-icon>
          <span class="pc-btn-text">返回列表</span>
        </el-button>
        <el-button size="small" :loading="overview.loading.value" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="danger" plain @click="confirmDrop">删除表</el-button>
      </template>
    </PageHeader>

    <ResourceState :loading="overview.loading.value" :error="overview.error.value" />

    <template v-if="table0">
      <PanelCard flush>
        <el-tabs v-model="tab" class="pc-tabs" @tab-change="onTabChange">
          <el-tab-pane label="详细信息" name="details" />
          <el-tab-pane label="Schema" name="schema" />
          <el-tab-pane label="变更" name="changes" />
          <el-tab-pane label="快照" name="snapshots" />
          <el-tab-pane label="标签" name="tags" />
          <el-tab-pane label="分支" name="branches" />
          <el-tab-pane label="分区" name="partitions" />
          <el-tab-pane label="权限" name="grants" />
          <el-tab-pane label="数据访问" name="access" />
        </el-tabs>

        <div class="pc-tab-body">
          <!------------------------------ 详细信息 -------------------------------->
          <template v-if="tab === 'details'">
            <div class="pc-stats pc-stats-tight" v-if="latest">
              <div class="pc-stat">
                <div class="pc-stat-label">记录数（最新快照）</div>
                <div class="pc-stat-value">{{ formatNumber(latest.recordCount) }}</div>
                <div class="pc-stat-foot">累计写入，含历史文件</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">数据文件数</div>
                <div class="pc-stat-value">{{ formatNumber(latest.fileCount) }}</div>
                <div class="pc-stat-foot">最新快照引用的文件</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">数据文件大小</div>
                <div class="pc-stat-value">{{ formatBytes(latest.fileSizeInBytes) }}</div>
                <div class="pc-stat-foot">不含快照与清单文件</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">最近文件写入</div>
                <div class="pc-stat-value pc-stat-value-sm">
                  {{ latest.lastFileCreationTime ? relativeTime(latest.lastFileCreationTime) : '—' }}
                </div>
                <div class="pc-stat-foot">最新快照 ID：{{ latest.snapshot?.id ?? '—' }}</div>
              </div>
            </div>

            <div class="pc-detail-grid">
              <dl class="pc-kv">
                <dt>表 ID</dt>
                <dd><CopyText :text="table0.id" /></dd>
                <dt>是否外部表</dt>
                <dd>{{ table0.isExternal ? '是（注册进来的表）' : '否（由本服务管理）' }}</dd>
                <dt>Schema 版本</dt>
                <dd class="pc-mono">{{ table0.schemaId ?? '—' }}</dd>
                <dt>路径</dt>
                <dd><CopyText :text="table0.path" /></dd>
                <dt>Owner</dt>
                <dd>{{ orPlaceholder(table0.owner) }}</dd>
                <dt>创建</dt>
                <dd><TimeText :millis="table0.createdAt" /> · {{ orPlaceholder(table0.createdBy) }}</dd>
                <dt>更新</dt>
                <dd><TimeText :millis="table0.updatedAt" /> · {{ orPlaceholder(table0.updatedBy) }}</dd>
              </dl>
              <JsonBlock
                :value="table0.schema?.options || {}"
                label="表属性（options）"
                empty-text="没有表属性"
                max-height="300px"
              />
            </div>
          </template>

          <!-------------------------------- Schema -------------------------------->
          <template v-else-if="tab === 'schema'">
            <el-table :data="table0.schema?.fields || []" size="small">
              <el-table-column prop="id" label="ID" width="64" align="right" />
              <el-table-column prop="name" label="字段名" min-width="160" />
              <el-table-column label="类型" min-width="180">
                <template #default="{ row }">
                  <span class="pc-mono">{{ typeText(row.type) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="description" label="注释" min-width="140" />
              <el-table-column prop="defaultValue" label="默认值" min-width="110" />
            </el-table>

            <dl class="pc-kv pc-schema-meta">
              <dt>分区键</dt>
              <dd>{{ (table0.schema?.partitionKeys || []).join(', ') || '（未分区）' }}</dd>
              <dt>主键</dt>
              <dd>{{ (table0.schema?.primaryKeys || []).join(', ') || '（无主键表）' }}</dd>
              <dt>表注释</dt>
              <dd>{{ orPlaceholder(table0.schema?.comment) }}</dd>
            </dl>
          </template>

          <!-------------------------------- 变更 ---------------------------------->
          <template v-else-if="tab === 'changes'">
            <el-alert type="info" :closable="false" show-icon class="pc-hint">
              <template #title>变更会一次性提交</template>
              <div>
                服务端的 alter 接口接收一组变更并原子应用。先在这里逐条添加，
                确认无误后再提交——提交后无法撤销，只能反向再做一次变更。
              </div>
            </el-alert>

            <div class="pc-change-form">
              <el-select v-model="builder.action" size="small" class="pc-change-action">
                <el-option
                  v-for="option in CHANGE_ACTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <template v-for="field in builderFields" :key="field.key">
                <el-checkbox v-if="field.type === 'bool'" v-model="builder.values[field.key]" size="small">
                  {{ field.label }}
                </el-checkbox>
                <el-input
                  v-else
                  v-model="builder.values[field.key]"
                  size="small"
                  :placeholder="field.label"
                  class="pc-change-input"
                />
              </template>
              <el-button size="small" type="primary" @click="addChange">
                <el-icon><Plus /></el-icon>
                <span class="pc-btn-text">添加</span>
              </el-button>
            </div>

            <el-table :data="pendingChanges" size="small" empty-text="还没有待提交的变更">
              <el-table-column label="变更" min-width="420">
                <template #default="{ row }">
                  <span class="pc-mono pc-break">{{ compact(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="70" align="right">
                <template #default="{ $index }">
                  <IconAction
                    icon="Close"
                    label="移除"
                    type="danger"
                    @click="pendingChanges.splice($index, 1)"
                  />
                </template>
              </el-table-column>
            </el-table>

            <div class="pc-change-foot">
              <el-button size="small" :disabled="!pendingChanges.length" @click="pendingChanges = []">
                清空
              </el-button>
              <el-button
                size="small"
                type="primary"
                :loading="submitting"
                :disabled="!pendingChanges.length"
                @click="submitChanges"
              >
                提交 {{ pendingChanges.length }} 项变更
              </el-button>
            </div>
          </template>

          <!-------------------------------- 快照 ---------------------------------->
          <template v-else-if="tab === 'snapshots'">
            <el-alert type="warning" :closable="false" show-icon class="pc-hint">
              <template #title>回滚会删除目标之后的提交记录</template>
              <div>
                回滚把最新快照指针退回到选定的快照，其后的提交记录随即被删除，无法恢复。
                数据文件不会被回收，它们会变成不再被引用的孤儿文件。
              </div>
            </el-alert>
            <div class="pc-obj-tools">
              <span class="pc-dim">按版本号查询指定快照：</span>
              <el-input v-model="snapshotVersion" size="small" placeholder="版本号，如 3" class="pc-version-input" />
              <el-button size="small" :loading="versionLoading" @click="fetchVersion">查询</el-button>
            </div>
            <el-table
              v-loading="snapshots.loading.value"
              :data="snapshots.data.value?.snapshots || []"
              size="small"
              empty-text="这张表还没有快照（还没有写入过数据）"
            >
              <el-table-column prop="version" label="版本" width="72" align="right" />
              <el-table-column label="快照 ID" min-width="170">
                <template #default="{ row }"><span class="pc-mono">{{ row.id }}</span></template>
              </el-table-column>
              <el-table-column prop="commitKind" label="提交类型" width="120" />
              <el-table-column prop="commitUser" label="提交者" min-width="110" />
              <el-table-column label="提交时间" width="170">
                <template #default="{ row }"><TimeText :millis="row.timeMillis" /></template>
              </el-table-column>
              <el-table-column label="总记录数" width="110" align="right">
                <template #default="{ row }">{{ formatNumber(row.totalRecordCount) }}</template>
              </el-table-column>
              <el-table-column label="增量记录数" width="110" align="right">
                <template #default="{ row }">{{ formatNumber(row.deltaRecordCount) }}</template>
              </el-table-column>
              <el-table-column label="" width="110" align="right">
                <template #default="{ row }">
                  <IconAction icon="View" label="详情" @click="showSnapshot(row)" />
                  <IconAction icon="RefreshLeft" label="回滚" type="danger" @click="rollbackToSnapshot(row)" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-------------------------------- 标签 ---------------------------------->
          <template v-else-if="tab === 'tags'">
            <div class="pc-obj-tools">
              <span class="pc-dim">标签把某个快照固定下来，使其不受快照过期清理影响。</span>
              <span class="pc-spacer" />
              <el-button size="small" @click="openCreateTag">
                <el-icon><Plus /></el-icon>
                <span class="pc-btn-text">新建标签</span>
              </el-button>
            </div>
            <el-table
              v-loading="tags.loading.value"
              :data="tags.data.value?.tags || []"
              size="small"
              empty-text="还没有标签"
            >
              <el-table-column prop="tagName" label="标签名" min-width="200" />
              <el-table-column label="指向快照" width="180">
                <template #default="{ row }">
                  <span class="pc-mono">{{ row.snapshotId ?? '最新' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" width="170">
                <template #default="{ row }"><TimeText :millis="row.tagCreateTime" /></template>
              </el-table-column>
              <el-table-column label="保留时长" width="110">
                <template #default="{ row }">{{ orPlaceholder(row.tagTimeRetained) }}</template>
              </el-table-column>
              <el-table-column label="" width="110" align="right">
                <template #default="{ row }">
                  <IconAction icon="RefreshLeft" label="回滚" type="danger" @click="rollbackToTag(row.tagName)" />
                  <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropTag(row.tagName)" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-------------------------------- 分支 ---------------------------------->
          <template v-else-if="tab === 'branches'">
            <div class="pc-obj-tools">
              <span class="pc-dim">分支是一条可写的历史线，常用于隔离试验或并发写入。</span>
              <span class="pc-spacer" />
              <el-button size="small" @click="openCreateBranch">
                <el-icon><Plus /></el-icon>
                <span class="pc-btn-text">新建分支</span>
              </el-button>
            </div>
            <el-table
              v-loading="branches.loading.value"
              :data="branches.data.value?.branches || []"
              size="small"
              empty-text="这张表还没有分支（主分支不在此列表中）"
            >
              <el-table-column prop="branchName" label="分支名" min-width="200">
                <template #default="{ row }">{{ row.branchName || row.name || row }}</template>
              </el-table-column>
              <el-table-column label="操作" width="160" align="right">
                <template #default="{ row }">
                  <IconAction icon="EditPen" label="重命名" @click="openRenameBranch(branchName(row))" />
                  <IconAction icon="DArrowRight" label="推进到最新" @click="forwardBranch(branchName(row))" />
                  <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropBranch(branchName(row))" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-------------------------------- 分区 ---------------------------------->
          <template v-else-if="tab === 'partitions'">
            <el-alert type="info" :closable="false" show-icon class="pc-hint">
              <template #title>这里的统计口径是最新快照的活跃数据</template>
              <div>分区级统计只计算被最新快照引用的文件，因此各分区之和可能小于仓库实际占用。</div>
            </el-alert>
            <el-table
              v-loading="partitions.loading.value"
              :data="partitions.data.value?.partitions || []"
              size="small"
              empty-text="这张表没有分区，或还没有数据"
            >
              <el-table-column label="分区" min-width="230">
                <template #default="{ row }">
                  <span class="pc-mono pc-break">{{ inlineMap(row.spec) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="记录数" width="110" align="right">
                <template #default="{ row }">{{ formatNumber(row.recordCount) }}</template>
              </el-table-column>
              <el-table-column label="文件数" width="90" align="right">
                <template #default="{ row }">{{ formatNumber(row.fileCount) }}</template>
              </el-table-column>
              <el-table-column label="大小" width="110" align="right">
                <template #default="{ row }">{{ formatBytes(row.fileSizeInBytes) }}</template>
              </el-table-column>
              <el-table-column label="桶数" width="80" align="right">
                <template #default="{ row }">{{ orPlaceholder(row.totalBuckets) }}</template>
              </el-table-column>
              <el-table-column label="已完成" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.done ? 'success' : 'info'" effect="plain">
                    {{ row.done ? '是' : '否' }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-------------------------------- 权限 ---------------------------------->
          <template v-else-if="tab === 'grants'">
            <el-alert type="info" :closable="false" show-icon class="pc-hint">
              <template #title>授权挂在 catalog 角色上，增删需要 CATALOG_MANAGE_ACCESS</template>
              <div>
                管理规格没有「按资源反查授权」的端点，这里的列表是把每个 catalog 角色的授权
                取回后过滤出作用到这张表的条目。新增授权逐项提交，服务端幂等，重复授予不报错。
              </div>
            </el-alert>

            <div class="pc-tg-form">
              <el-select v-model="grantForm.role" size="small" filterable placeholder="Catalog 角色" class="pc-tg-role">
                <el-option v-for="role in grantRoles" :key="role" :label="role" :value="role" />
              </el-select>
              <el-select
                v-model="grantForm.privileges"
                size="small"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                placeholder="选择权限（可多选）"
                class="pc-tg-priv"
              >
                <el-option v-for="privilege in tablePrivileges" :key="privilege" :label="privilege" :value="privilege" />
              </el-select>
              <el-button
                size="small"
                type="primary"
                :loading="grantSaving"
                :disabled="!grantForm.role || !grantForm.privileges.length"
                @click="submitGrants"
              >
                授予{{ grantForm.privileges.length ? `（${grantForm.privileges.length}）` : '' }}
              </el-button>
              <el-button size="small" :loading="grantsState.loading" @click="loadGrants">刷新</el-button>
            </div>

            <el-alert v-if="grantsState.error" type="error" :closable="false" show-icon class="pc-hint">
              <template #title>加载授权失败</template>
              <div>{{ grantsState.error }}</div>
            </el-alert>

            <el-table
              v-loading="grantsState.loading"
              :data="grantsState.rows"
              size="small"
              empty-text="还没有 catalog 角色的授权作用到这张表"
            >
              <el-table-column prop="roleName" label="Catalog 角色" min-width="180" />
              <el-table-column label="权限" min-width="280">
                <template #default="{ row }"><span class="pc-mono">{{ row.privilege }}</span></template>
              </el-table-column>
              <el-table-column label="" width="70" align="right">
                <template #default="{ row }">
                  <IconAction icon="Remove" label="撤销" type="danger" @click="revokeTableGrant(row)" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!------------------------------- 数据访问 -------------------------------->
          <template v-else>
            <div class="pc-access">
              <PanelCard title="数据访问令牌" hint="GET .../token">
                <p class="pc-dim pc-access-text">
                  服务端按 catalog 的存储配置下发临时凭据。若服务端以
                  <code class="pc-mono">paimon.rest.credential-manager.type=noop</code> 运行，
                  这里会返回 501——那种部署下由引擎自带的云凭据链负责鉴权。
                </p>
                <el-button size="small" :loading="tokenLoading" @click="fetchToken">
                  <el-icon><Key /></el-icon>
                  <span class="pc-btn-text">获取令牌</span>
                </el-button>
                <div v-if="tokenError" class="pc-access-error">{{ tokenError }}</div>
                <JsonBlock v-if="tokenData" :value="tokenData" class="pc-access-json" max-height="300px" />
              </PanelCard>

              <PanelCard title="查询级授权规则" hint="POST .../auth">
                <p class="pc-dim pc-access-text">
                  传入要查询的列，服务端按调用主体的授权返回行过滤表达式与列脱敏规则。
                  这两项是引擎侧生效的，控制台只负责展示服务端给出了什么。
                </p>
                <el-input
                  v-model="authSelect"
                  size="small"
                  type="textarea"
                  :rows="3"
                  placeholder="要查询的列名，逗号或换行分隔；留空表示查询全部列"
                />
                <el-button size="small" class="pc-access-btn" :loading="authLoading" @click="fetchAuth">
                  查询规则
                </el-button>
                <div v-if="authError" class="pc-access-error">{{ authError }}</div>
                <JsonBlock v-if="authData" :value="authData" class="pc-access-json" max-height="300px" />
              </PanelCard>
            </div>
          </template>
        </div>
      </PanelCard>
    </template>

    <!-- 快照详情 -->
    <el-drawer v-model="snapshotDrawer.open" :title="snapshotDrawer.title" size="620px">
      <JsonBlock :value="snapshotDrawer.payload" />
    </el-drawer>

    <!-- 新建标签 -->
    <el-dialog v-model="tagDialog.open" title="新建标签" width="480px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small">
        <el-form-item label="标签名" required>
          <el-input v-model="tagDialog.tagName" />
        </el-form-item>
        <el-form-item label="快照 ID">
          <el-input v-model="tagDialog.snapshotId" placeholder="留空表示指向最新快照" />
        </el-form-item>
        <el-form-item label="保留时长（timeRetained）">
          <el-input v-model="tagDialog.timeRetained" placeholder="例如 7d / 12h / 30m" />
        </el-form-item>
        <el-form-item label="已存在时忽略（ignoreIfExists）">
          <el-switch v-model="tagDialog.ignoreIfExists" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="tagDialog.saving" @click="saveTag">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建分支 -->
    <el-dialog v-model="branchDialog.open" title="新建分支" width="460px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small">
        <el-form-item label="分支名" required>
          <el-input v-model="branchDialog.branch" />
        </el-form-item>
        <el-form-item label="从标签创建（fromTag）">
          <el-input v-model="branchDialog.fromTag" placeholder="留空表示从主分支最新快照创建" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="branchDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="branchDialog.saving" @click="saveBranch">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import CopyText from '@/components/CopyText.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TimeText from '@/components/TimeText.vue'
import { catalogApi } from '@/api/catalog.js'
import { managementApi } from '@/api/management.js'
import { describeError } from '@/api/client.js'
import { confirmDanger, notifyError, notifySuccess, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import {
  formatBytes,
  formatNumber,
  inlineMap,
  orPlaceholder,
  relativeTime,
} from '@/utils/format.js'

/**
 * 表详情。九个页签：八个对应 catalog API 里以单张表为作用域的端点，
 * 另有一个组合页签——「详细信息」汇总单表元数据（基本信息 + 快照统计 + options），
 * 「权限」组合管理 API 的授权端点。
 *
 * <p>「详细信息」「Schema」「变更」「数据访问」只用 overview 已有数据，
 * 其余页签按需加载：切到某个页签才发它的请求。
 * 打开一张表就发两个请求，在远端仓库上会让页面明显变慢。
 */

const route = useRoute()

const prefix = computed(() => route.params.prefix)
const database = computed(() => route.params.database)
const table = computed(() => route.params.table)

const tab = ref('details')

/** 不需要额外请求的页签：切过去不触发 load。 */
const SELF_CONTAINED_TABS = new Set(['details', 'schema', 'changes', 'access'])

const overview = useResource(async () => {
  const [detail, snapshot] = await Promise.all([
    catalogApi.getTable(prefix.value, database.value, table.value),
    catalogApi.tableSnapshot(prefix.value, database.value, table.value).catch(() => null),
  ])
  return { detail, snapshot }
})

const table0 = computed(() => overview.data.value?.detail || null)
const latest = computed(() => overview.data.value?.snapshot?.snapshot || null)

const snapshots = useResource(
  () => catalogApi.listSnapshots(prefix.value, database.value, table.value),
  { immediate: false },
)
const tags = useResource(
  () => catalogApi.listTags(prefix.value, database.value, table.value),
  { immediate: false },
)
const branches = useResource(
  () => catalogApi.listBranches(prefix.value, database.value, table.value),
  { immediate: false },
)
const partitions = useResource(
  () => catalogApi.listPartitions(prefix.value, database.value, table.value),
  { immediate: false },
)

const loadedTabs = new Set()

function onTabChange(name) {
  if (loadedTabs.has(name)) return
  loadedTabs.add(name)
  if (name === 'snapshots') snapshots.load()
  if (name === 'tags') tags.load()
  if (name === 'branches') branches.load()
  if (name === 'partitions') partitions.load()
  if (name === 'grants') loadGrants()
}

function reloadAll() {
  overview.load()
  loadedTabs.clear()
  onTabChange(tab.value)
}

watch([prefix, database, table], () => {
  loadedTabs.clear()
  overview.load()
  if (!SELF_CONTAINED_TABS.has(tab.value)) {
    loadedTabs.add(tab.value)
    onTabChange(tab.value)
  }
}, { immediate: true })

function typeText(type) {
  if (type === null || type === undefined) return '—'
  return typeof type === 'string' ? type : JSON.stringify(type)
}

// ------------------------------------------------------------------ 变更

const CHANGE_ACTIONS = [
  { value: 'setOption', label: '设置表属性（setOption）', fields: [
    { key: 'key', label: '键' }, { key: 'value', label: '值' }] },
  { value: 'removeOption', label: '删除表属性（removeOption）', fields: [{ key: 'key', label: '键' }] },
  { value: 'updateComment', label: '修改表注释（updateComment）', fields: [{ key: 'comment', label: '注释' }] },
  { value: 'addColumn', label: '新增列（addColumn）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'dataType', label: '类型，如 BIGINT' },
    { key: 'comment', label: '注释（可选）' }] },
  { value: 'dropColumn', label: '删除列（dropColumn）', fields: [{ key: 'fieldName', label: '列名' }] },
  { value: 'renameColumn', label: '重命名列（renameColumn）', fields: [
    { key: 'fieldName', label: '原列名' }, { key: 'newName', label: '新列名' }] },
  { value: 'updateColumnType', label: '修改列类型（updateColumnType）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'newDataType', label: '新类型' }] },
  { value: 'updateColumnComment', label: '修改列注释（updateColumnComment）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'newComment', label: '新注释' }] },
  { value: 'updateColumnNullability', label: '修改列可空性（updateColumnNullability）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'newNullability', label: '允许为空', type: 'bool' }] },
  { value: 'move', label: '调整列位置（updateColumnPosition）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'referenceFieldName', label: '参照列' }] },
]

const builder = reactive({ action: 'setOption', values: {} })

const builderFields = computed(
  () => CHANGE_ACTIONS.find((action) => action.value === builder.action)?.fields || [],
)

const pendingChanges = ref([])
const submitting = ref(false)

/** 按动作名把表单值收敛成规格里的 `SchemaChange` 形状。 */
function toChange(action, values) {
  const text = (key) => String(values[key] ?? '').trim()
  switch (action) {
    case 'setOption':
      return { action, key: text('key'), value: text('value') }
    case 'removeOption':
      return { action, key: text('key') }
    case 'updateComment':
      return { action, comment: text('comment') || null }
    case 'addColumn': {
      const change = { action, fieldNames: [text('fieldName')], dataType: text('dataType') }
      if (text('comment')) change.comment = text('comment')
      return change
    }
    case 'dropColumn':
      return { action, fieldNames: [text('fieldName')] }
    case 'renameColumn':
      return { action, fieldNames: [text('fieldName')], newName: text('newName') }
    case 'updateColumnType':
      return { action, fieldNames: [text('fieldName')], newDataType: text('newDataType') }
    case 'updateColumnComment':
      return { action, fieldNames: [text('fieldName')], newComment: text('newComment') }
    case 'updateColumnNullability':
      return { action, fieldNames: [text('fieldName')], newNullability: Boolean(values.newNullability) }
    case 'move':
      return { action: 'updateColumnPosition', move: {
        fieldName: text('fieldName'),
        referenceFieldName: text('referenceFieldName'),
        type: 'after',
      } }
    default:
      return { action }
  }
}

/** 变更项的必填校验：缺了关键字段就在前端拦住，不要留到服务端报 400。 */
function addChange() {
  const fields = builderFields.value.filter((field) => field.type !== 'bool'
    && !field.label.includes('可选')
    && !['comment', 'newComment', 'value'].includes(field.key))
  for (const field of fields) {
    if (!String(builder.values[field.key] ?? '').trim()) {
      notifyError(new Error(`请填写「${field.label}」`))
      return
    }
  }
  pendingChanges.value.push(toChange(builder.action, builder.values))
  builder.values = {}
}

function compact(change) {
  const rest = { ...change }
  delete rest.action
  return `${change.action} ${JSON.stringify(rest)}`
}

async function submitChanges() {
  submitting.value = true
  try {
    await catalogApi.alterTable(prefix.value, database.value, table.value, {
      changes: pendingChanges.value,
    })
    notifySuccess(`已提交 ${pendingChanges.value.length} 项变更`)
    pendingChanges.value = []
    builder.values = {}
    overview.load()
  } catch (error) {
    notifyError(error)
  } finally {
    submitting.value = false
  }
}

// ------------------------------------------------------------------ 快照

const snapshotDrawer = reactive({ open: false, title: '', payload: null })
const snapshotVersion = ref('')
const versionLoading = ref(false)

function showSnapshot(row) {
  snapshotDrawer.title = `快照：版本 ${row.version}`
  snapshotDrawer.payload = row
  snapshotDrawer.open = true
}

async function fetchVersion() {
  const version = snapshotVersion.value.trim()
  if (!version) return
  versionLoading.value = true
  try {
    const payload = await catalogApi.getSnapshotByVersion(prefix.value, database.value, table.value, version)
    const snapshot = payload?.snapshot || payload
    snapshotDrawer.title = `快照：版本 ${version}`
    snapshotDrawer.payload = snapshot
    snapshotDrawer.open = true
  } catch (error) {
    notifyError(error)
  } finally {
    versionLoading.value = false
  }
}

/**
 * 回滚到指定快照。
 *
 * <p>这是本页面破坏性最强的操作，确认框里必须说清两件事：目标快照之后的提交会被丢弃，
 * 以及数据文件不会被回收（回滚只是把指针退回去）。第二点尤其容易被误解为「撤销写入」。
 */
async function rollbackToSnapshot(row) {
  const ok = await confirmDanger({
    title: '回滚表',
    target: `把表回滚到快照 ${row.id}（版本 ${row.version}）？`,
    detail: '目标快照之后的全部提交记录会被删除，这些提交无法恢复。'
      + '回滚只移动指针，不会删除已写入的数据文件——它们会变成不再被引用的孤儿文件。'
      + '若有客户端正在写入这张表，请在回滚前先停止写入。',
    confirmText: '回滚',
  })
  if (!ok) return
  await submitRollback({ type: 'snapshot', snapshotId: row.id })
}

async function rollbackToTag(tagName) {
  const ok = await confirmDanger({
    title: '回滚表',
    target: `把表回滚到标签「${tagName}」指向的快照？`,
    detail: '目标快照之后的全部提交记录会被删除，这些提交无法恢复。'
      + '标签本身保留，仍指向它创建时的快照。',
    confirmText: '回滚',
  })
  if (!ok) return
  await submitRollback({ type: 'tag', tagName })
}

async function submitRollback(instant) {
  try {
    await catalogApi.rollbackTable(prefix.value, database.value, table.value, instant)
    notifySuccess('已回滚')
    overview.load()
    snapshots.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 标签

const tagDialog = reactive({
  open: false,
  tagName: '',
  snapshotId: '',
  timeRetained: '',
  ignoreIfExists: true,
  saving: false,
})

function openCreateTag() {
  Object.assign(tagDialog, {
    open: true,
    tagName: '',
    snapshotId: '',
    timeRetained: '',
    ignoreIfExists: true,
  })
}

async function saveTag() {
  if (!tagDialog.tagName.trim()) {
    notifyError(new Error('请填写标签名'))
    return
  }
  tagDialog.saving = true
  try {
    const body = { tagName: tagDialog.tagName.trim() }
    if (tagDialog.snapshotId.trim()) body.snapshotId = Number(tagDialog.snapshotId.trim())
    if (tagDialog.timeRetained.trim()) body.timeRetained = tagDialog.timeRetained.trim()
    body.ignoreIfExists = tagDialog.ignoreIfExists
    await catalogApi.createTag(prefix.value, database.value, table.value, body)
    notifySuccess(`已创建标签 ${tagDialog.tagName.trim()}`)
    tagDialog.open = false
    tags.load()
  } catch (error) {
    notifyError(error)
  } finally {
    tagDialog.saving = false
  }
}

async function confirmDropTag(name) {
  const ok = await confirmDanger({
    title: '删除标签',
    target: `确认删除标签「${name}」？`,
    detail: '删除后，该快照不再受标签保护，快照过期时可能被清理。',
  })
  if (!ok) return
  try {
    await catalogApi.dropTag(prefix.value, database.value, table.value, name)
    notifySuccess(`已删除标签 ${name}`)
    tags.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 分支

const branchDialog = reactive({ open: false, branch: '', fromTag: '', saving: false })

function branchName(row) {
  return row.branchName || row.name || String(row)
}

function openCreateBranch() {
  Object.assign(branchDialog, { open: true, branch: '', fromTag: '' })
}

async function saveBranch() {
  if (!branchDialog.branch.trim()) {
    notifyError(new Error('请填写分支名'))
    return
  }
  branchDialog.saving = true
  try {
    const body = { branch: branchDialog.branch.trim() }
    if (branchDialog.fromTag.trim()) body.fromTag = branchDialog.fromTag.trim()
    await catalogApi.createBranch(prefix.value, database.value, table.value, body)
    notifySuccess(`已创建分支 ${body.branch}`)
    branchDialog.open = false
    branches.load()
  } catch (error) {
    notifyError(error)
  } finally {
    branchDialog.saving = false
  }
}

async function openRenameBranch(name) {
  const next = window.prompt(`把分支「${name}」重命名为`, name)
  if (!next || next === name) return
  try {
    await catalogApi.renameBranch(prefix.value, database.value, table.value, name, { toBranch: next })
    notifySuccess('已重命名分支')
    branches.load()
  } catch (error) {
    notifyError(error)
  }
}

async function forwardBranch(name) {
  const ok = await confirmDanger({
    title: '推进分支',
    target: `把分支「${name}」推进到主分支的最新快照？`,
    detail: '推进后该分支的历史会指向新的快照，原分支上的独立提交将成为不可达的历史。',
    confirmText: '推进',
  })
  if (!ok) return
  try {
    await catalogApi.forwardBranch(prefix.value, database.value, table.value, name, { branch: name })
    notifySuccess('已推进分支')
    branches.load()
  } catch (error) {
    notifyError(error)
  }
}

async function confirmDropBranch(name) {
  const ok = await confirmDanger({
    title: '删除分支',
    target: `确认删除分支「${name}」？`,
    detail: '只删除分支指针，其指向的快照是否保留取决于是否另有标签引用。',
  })
  if (!ok) return
  try {
    await catalogApi.dropBranch(prefix.value, database.value, table.value, name)
    notifySuccess(`已删除分支 ${name}`)
    branches.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 数据访问

const tokenLoading = ref(false)
const tokenError = ref('')
const tokenData = ref(null)

async function fetchToken() {
  tokenLoading.value = true
  tokenError.value = ''
  try {
    tokenData.value = await catalogApi.tableToken(prefix.value, database.value, table.value)
  } catch (error) {
    tokenData.value = null
    tokenError.value = describeError(error)
  } finally {
    tokenLoading.value = false
  }
}

const authSelect = ref('')
const authLoading = ref(false)
const authError = ref('')
const authData = ref(null)

async function fetchAuth() {
  authLoading.value = true
  authError.value = ''
  try {
    const select = authSelect.value
      .split(/[,\n]/)
      .map((item) => item.trim())
      .filter(Boolean)
    authData.value = await catalogApi.tableAuth(
      prefix.value,
      database.value,
      table.value,
      select.length ? { select } : {},
    )
  } catch (error) {
    authData.value = null
    authError.value = describeError(error)
  } finally {
    authLoading.value = false
  }
}

// ------------------------------------------------------------------ 权限

/**
 * 表级授权。管理规格把授权挂在 catalog role 上，没有「按资源反查授权」的端点，
 * 所以列表是把全部角色的授权拉回来后在控制台侧过滤——角色数量通常不大，这个 N+1 可以接受。
 */
const grantsState = reactive({ loading: false, loaded: false, roles: [], rows: [], error: '' })

const grantRoles = computed(() => grantsState.roles)

/** meta 接口按资源层级下发可授予的权限；meta 不可用时退化到常用集合。 */
const FALLBACK_TABLE_PRIVILEGES = [
  'CATALOG_MANAGE_ACCESS',
  'TABLE_DROP',
  'TABLE_LIST',
  'TABLE_READ_PROPERTIES',
  'TABLE_WRITE_PROPERTIES',
  'TABLE_READ_DATA',
  'TABLE_WRITE_DATA',
  'TABLE_FULL_METADATA',
]

const tablePrivileges = computed(() => {
  const map = session.state.meta?.enums?.privilegesByGrantType || {}
  return map.table || FALLBACK_TABLE_PRIVILEGES
})

async function loadGrants() {
  grantsState.loading = true
  grantsState.error = ''
  try {
    const { roles } = await managementApi.listCatalogRoles(prefix.value)
    const names = (roles || []).map((role) => role.name)
    grantsState.roles = names
    const rows = []
    for (const name of names) {
      const payload = await managementApi.listGrants(prefix.value, name)
      for (const grant of payload?.grants || []) {
        if (grant.type !== 'table') continue
        if ((grant.namespace || []).join('.') !== database.value) continue
        if (grant.tableName !== table.value) continue
        rows.push({ roleName: name, privilege: grant.privilege, grant })
      }
    }
    rows.sort((a, b) => a.roleName.localeCompare(b.roleName) || a.privilege.localeCompare(b.privilege))
    grantsState.rows = rows
    grantsState.loaded = true
  } catch (error) {
    grantsState.error = describeError(error)
  } finally {
    grantsState.loading = false
  }
}

const grantForm = reactive({ role: '', privileges: [] })
const grantSaving = ref(false)

async function submitGrants() {
  grantSaving.value = true
  try {
    for (const privilege of grantForm.privileges) {
      await managementApi.addGrant(prefix.value, grantForm.role, {
        type: 'table',
        namespace: [database.value],
        tableName: table.value,
        privilege,
      })
    }
    notifySuccess(`已授予 ${grantForm.role} ${grantForm.privileges.length} 项权限`)
    grantForm.privileges = []
    await loadGrants()
  } catch (error) {
    notifyError(error)
  } finally {
    grantSaving.value = false
  }
}

async function revokeTableGrant(row) {
  const ok = await confirmDanger({
    title: '撤销授权',
    target: `撤销 ${row.roleName} 在这张表上的 ${row.privilege}？`,
    detail: '撤销后，持有该角色的主体将立即失去对应权限。撤销不存在的授权会返回 404。',
    confirmText: '撤销',
  })
  if (!ok) return
  try {
    await managementApi.revokeGrant(prefix.value, row.roleName, row.grant)
    notifySuccess(`已撤销 ${row.roleName} 的 ${row.privilege}`)
    await loadGrants()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 删表

async function confirmDrop() {
  const ok = await confirmDanger({
    title: '删除表',
    target: `确认删除表「${table.value}」？`,
    detail: '元数据与快照历史会被移除，数据文件不会自动清理。',
  })
  if (!ok) return
  try {
    await catalogApi.dropTable(prefix.value, database.value, table.value)
    notifySuccess('已删除表')
    window.location.assign(`/console/browse?catalog=${encodeURIComponent(prefix.value)}&database=${encodeURIComponent(database.value)}`)
  } catch (error) {
    notifyError(error)
  }
}
</script>

<style scoped>

.pc-tabs {
  padding: 0 16px;
}

.pc-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.pc-tab-body {
  padding: 14px 16px;
}

.pc-stats-tight {
  margin: 14px 0;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
}

.pc-stat-value-sm {
  font-size: 17px;
}

.pc-schema-meta {
  margin-top: 16px;
}

.pc-detail-grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(280px, 1fr);
  gap: 16px;
  margin-top: 16px;
}

.pc-tg-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.pc-tg-role {
  width: 220px;
}

.pc-tg-priv {
  width: 380px;
}

.pc-change-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.pc-change-action {
  width: 260px;
}

.pc-change-input {
  width: 180px;
}

.pc-change-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.pc-version-input {
  width: 150px;
}

.pc-obj-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 13px;
}

.pc-access {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.pc-access-text {
  font-size: 13px;
  line-height: 1.7;
  margin: 0 0 10px;
}

.pc-access-btn {
  margin-top: 8px;
}

.pc-access-error {
  margin-top: 10px;
  color: var(--el-color-danger);
  font-size: 13px;
  line-height: 1.6;
}

.pc-access-json {
  margin-top: 12px;
}

.pc-access-text code {
  padding: 1px 4px;
  background: var(--pc-surface-2);
  border: 1px solid var(--pc-border);
  border-radius: 4px;
}

@media (max-width: 1080px) {
  .pc-access,
  .pc-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
