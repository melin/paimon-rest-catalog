<template>
  <div class="pc-page pc-browse">
    <PageHeader
      title="Catalog"
      :subtitle="`浏览 catalog「${prefix || '未选择'}」下的库与对象。列表与操作走 catalog API（/v1/{prefix}/...），与引擎客户端看到的是同一份元数据。`"
    >
      <template #actions>
        <el-button size="small" @click="reloadDatabases">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" :disabled="!database" @click="openCreateTable">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建表</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreateDatabase">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建数据库</span>
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="!prefix" type="warning" :closable="false" show-icon class="pc-hint">
      <template #title>还没有可浏览的 catalog</template>
      <div>
        服务端没有登记任何 catalog。先在「Catalog 管理」里创建一个，
        或确认 <code class="pc-mono">paimon.rest.auto-create-catalog</code> 是否开启。
      </div>
    </el-alert>

    <div v-else class="pc-browse-body">
      <!-- 左：数据库 -->
      <aside class="pc-db-pane pc-card">
        <div class="pc-pane-head">
          <span class="pc-pane-title">数据库</span>
          <span class="pc-dim">{{ databases.length }}</span>
        </div>
        <div class="pc-pane-tools">
          <el-input v-model="dbFilter" size="small" placeholder="过滤数据库" clearable>
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
        <div class="pc-db-list pc-scroll">
          <el-skeleton v-if="dbLoading" :rows="4" animated class="pc-db-skeleton" />
          <el-alert v-else-if="dbError" type="error" :closable="false" show-icon :title="dbError" />
          <el-empty v-else-if="!filteredDatabases.length" :image-size="54" description="没有匹配的数据库" />
          <template v-else>
            <div
              v-for="name in filteredDatabases"
              :key="name"
              class="pc-db-item"
              :class="{ 'is-active': name === database }"
              @click="selectDatabase(name)"
            >
              <el-icon class="pc-db-icon"><Folder /></el-icon>
              <span class="pc-db-name" :title="name">{{ name }}</span>
              <el-icon class="pc-db-drop" title="删除数据库" @click.stop="confirmDropDatabase(name)">
                <Delete />
              </el-icon>
            </div>
          </template>
        </div>
      </aside>

      <!-- 右：对象 -->
      <section class="pc-obj-pane pc-card">
        <div class="pc-pane-head">
          <span class="pc-pane-title">
            {{ database || '未选择数据库' }}
          </span>
          <span class="pc-spacer" />
          <el-button
            v-if="database"
            size="small"
            text
            type="danger"
            @click="confirmDropDatabase(database)"
          >
            删除数据库
          </el-button>
        </div>

        <el-tabs v-model="tab" class="pc-tabs">
          <el-tab-pane label="表" name="tables" />
          <el-tab-pane label="视图" name="views" />
          <el-tab-pane label="函数" name="functions" />
          <el-tab-pane label="语义视图" name="semanticViews" />
        </el-tabs>

        <div class="pc-obj-body">
          <ResourceState
            :loading="currentList.loading.value"
            :error="currentList.error.value"
            :empty="!currentList.items.value.length"
            :empty-text="emptyText"
          >
            <!-- 表 -->
            <template v-if="tab === 'tables'">
              <div class="pc-obj-tools">
                <el-input v-model="tableFilter" size="small" placeholder="过滤表名" clearable class="pc-filter">
                  <template #prefix><el-icon><Search /></el-icon></template>
                </el-input>
                <el-button size="small" @click="openLocateTable">按表 ID 定位</el-button>
                <el-button size="small" @click="openRegisterTable">注册已有表</el-button>
              </div>
              <el-table v-loading="tablesList.loading.value" :data="filteredTables" size="small">
                <el-table-column label="表名" min-width="200">
                  <template #default="{ row }">
                    <RouterLink
                      class="pc-link"
                      :to="{ name: 'table', params: { prefix, database, table: row } }"
                    >
                      {{ row }}
                    </RouterLink>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="110" align="right">
                  <template #default="{ row }">
                    <IconAction icon="EditPen" label="重命名" @click="openRenameTable(row)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropTable(row)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!-- 视图 -->
            <template v-else-if="tab === 'views'">
              <div class="pc-obj-tools">
                <span class="pc-dim">SQL 视图，定义由服务端整篇保存。</span>
                <span class="pc-spacer" />
                <el-button size="small" @click="openCreateView">
                  <el-icon><Plus /></el-icon>
                  <span class="pc-btn-text">新建视图</span>
                </el-button>
              </div>
              <el-table v-loading="viewsList.loading.value" :data="viewsList.items.value" size="small">
                <el-table-column prop="name" label="视图名" min-width="200" />
                <el-table-column label="操作" width="150" align="right">
                  <template #default="{ row }">
                    <IconAction icon="View" label="详情" @click="showView(row)" />
                    <IconAction icon="EditPen" label="重命名" @click="openRenameView(row.name)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropView(row.name)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!-- 函数 -->
            <template v-else-if="tab === 'functions'">
              <div class="pc-obj-tools">
                <span class="pc-dim">函数定义支持 file / sql / lambda 三种，控制台按 JSON 原样提交。</span>
                <span class="pc-spacer" />
                <el-button size="small" @click="openCreateFunction">
                  <el-icon><Plus /></el-icon>
                  <span class="pc-btn-text">新建函数</span>
                </el-button>
              </div>
              <el-table v-loading="functionsList.loading.value" :data="functionsList.items.value" size="small">
                <el-table-column prop="name" label="函数名" min-width="200" />
                <el-table-column label="操作" width="110" align="right">
                  <template #default="{ row }">
                    <IconAction icon="View" label="详情" @click="showFunction(row)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropFunction(row.name)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!-- 语义视图 -->
            <template v-else>
              <div class="pc-obj-tools">
                <span class="pc-dim">语义视图按格式整篇保存（上限 1 MiB）。</span>
              </div>
              <el-table v-loading="semanticViewsList.loading.value" :data="semanticViewsList.items.value" size="small">
                <el-table-column prop="name" label="名称" min-width="200" />
                <el-table-column label="操作" width="150" align="right">
                  <template #default="{ row }">
                    <IconAction icon="View" label="查看定义" @click="showSemanticView(row)" />
                    <IconAction icon="Edit" label="编辑" @click="openUpsertSemanticView(row)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropSemanticView(row)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <div v-if="currentList.token.value" class="pc-more">
              <el-button size="small" :loading="currentList.loading.value" @click="currentList.load(false)">
                加载更多
              </el-button>
              <span class="pc-dim">服务端返回了下一页令牌，当前列表未包含全部对象。</span>
            </div>
          </ResourceState>
        </div>
      </section>
    </div>

    <!-- 新建数据库 -->
    <el-dialog v-model="dbDialog.open" title="新建数据库" width="480px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small">
        <el-form-item label="名称" required>
          <el-input v-model="dbDialog.name" placeholder="例如 ods、dwd" />
        </el-form-item>
      </el-form>
      <el-divider content-position="left">选项（options）</el-divider>
      <PropertiesEditor v-model="dbDialog.options" hint="例如 owner；留空即可" />
      <template #footer>
        <el-button @click="dbDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dbDialog.saving" @click="saveDatabase">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建表 -->
    <el-dialog
      v-model="tableDialog.open"
      title="新建表"
      width="760px"
      :close-on-click-modal="false"
      top="6vh"
      destroy-on-close
    >
      <el-form label-position="top" size="small">
        <el-form-item label="表名" required>
          <el-input v-model="tableDialog.name" placeholder="例如 orders" />
        </el-form-item>
      </el-form>

      <el-divider content-position="left">字段（fields）</el-divider>
      <div class="pc-fields">
        <div v-for="(field, index) in tableDialog.fields" :key="index" class="pc-field-row">
          <el-input v-model="field.name" size="small" placeholder="字段名" class="pc-field-name" />
          <!-- 不用原生 datalist：Chrome 会给带 list 的 input 自带一个下拉指示器，
               嵌在 Element 的 wrapper 里位置错乱；el-select 的 allow-create 同样支持手工输入。 -->
          <el-select
            v-model="field.type"
            filterable
            allow-create
            default-first-option
            size="small"
            placeholder="类型，如 BIGINT / VARCHAR(64)"
            class="pc-field-type"
          >
            <el-option v-for="type in PAIMON_TYPES" :key="type" :label="type" :value="type" />
          </el-select>
          <el-input v-model="field.description" size="small" placeholder="注释（可选）" class="pc-field-desc" />
          <el-button size="small" text type="danger" @click="removeField(index)">
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
        <el-button size="small" text type="primary" @click="tableDialog.fields.push({ name: '', type: '', description: '' })">
          <el-icon><Plus /></el-icon>
          <span class="pc-field-add">添加字段</span>
        </el-button>
        <div class="pc-tip">字段 id 由服务端按顺序补齐，这里不需要填。</div>
      </div>

      <el-divider content-position="left">分区与主键（可选）</el-divider>
      <el-form label-position="top" size="small">
        <el-form-item>
          <template #label>
            <FieldLabel label="分区键（partitionKeys）">
              选项来自上面已填好的字段名，从中多选即可。分区键不能脱离主键：填了主键之后，
              这里只能选主键里已有的字段。改了字段名，这里的选项与已选值会跟着变。
            </FieldLabel>
          </template>
          <el-select
            v-model="tableDialog.partitionKeys"
            multiple
            filterable
            class="pc-full"
            :disabled="!fieldNames.length"
            :placeholder="fieldNames.length ? '从上面填好的字段名中选择（可多选）' : '先在上方填写字段名与类型'"
          >
            <el-option v-for="name in fieldNames" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <template #label>
            <FieldLabel label="主键（primaryKeys）">
              选项同样来自上面已填好的字段名。选了主键，这张表就是 Paimon 主键表
              （同主键的行按 merge-engine 归并）；一个都不选则是 append 表。
            </FieldLabel>
          </template>
          <el-select
            v-model="tableDialog.primaryKeys"
            multiple
            filterable
            class="pc-full"
            :disabled="!fieldNames.length"
            :placeholder="fieldNames.length ? '从上面填好的字段名中选择（可多选）' : '先在上方填写字段名与类型'"
          >
            <el-option v-for="name in fieldNames" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="表注释（comment）">
          <el-input v-model="tableDialog.comment" />
        </el-form-item>
      </el-form>

      <el-divider content-position="left">表属性（options）</el-divider>
      <PropertiesEditor
        v-model="tableDialog.options"
        hint="例如 bucket = 4、merge-engine = deduplicate"
      />

      <template #footer>
        <el-button @click="tableDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="tableDialog.saving" @click="saveTable">创建</el-button>
      </template>
    </el-dialog>

    <!-- 按表 ID 定位 -->
    <el-dialog v-model="locateDialog.open" title="按表 ID 定位" width="520px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small">
        <el-form-item required>
          <template #label>
            <FieldLabel label="表 ID">
              表 ID 在表详情页与引擎日志里都会出现。定位不需要知道它在哪个库，
              服务端会返回它所属的 database 与表名。
            </FieldLabel>
          </template>
          <el-input v-model="locateDialog.tableId" placeholder="例如 8f2c1e0a-..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="locateDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="locateDialog.saving" @click="locateTable">定位</el-button>
      </template>
    </el-dialog>

    <!-- 注册已有表 -->
    <el-dialog v-model="registerDialog.open" title="注册已有表" width="520px" :close-on-click-modal="false"
      destroy-on-close>
      <el-alert type="info" :closable="false" show-icon class="pc-hint">
        <template #title>注册只写元数据，不搬动数据</template>
        <div>路径下必须已存在一张完整的 Paimon 表（含 schema 与快照目录），否则读取时会失败。</div>
      </el-alert>
      <el-form label-position="top" size="small">
        <el-form-item label="表名" required>
          <el-input v-model="registerDialog.name" />
        </el-form-item>
        <el-form-item label="表路径（path）" required>
          <el-input v-model="registerDialog.path" placeholder="file:///tmp/paimon-warehouse/default.db/orders" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="registerDialog.saving" @click="saveRegister">注册</el-button>
      </template>
    </el-dialog>

    <!-- 重命名 -->
    <el-dialog v-model="renameDialog.open" :title="renameDialog.title" width="440px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small">
        <el-form-item label="新名称" required>
          <el-input v-model="renameDialog.value" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="renameDialog.saving" @click="saveRename">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新建视图 -->
    <el-dialog v-model="viewDialog.open" title="新建视图" width="640px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small">
        <el-form-item label="视图名" required>
          <el-input v-model="viewDialog.name" />
        </el-form-item>
        <el-form-item label="查询语句（query）" required>
          <el-input v-model="viewDialog.query" type="textarea" :rows="5" placeholder="SELECT id, amount FROM orders WHERE amount > 0" />
        </el-form-item>
        <el-form-item label="注释（comment）">
          <el-input v-model="viewDialog.comment" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="viewDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="viewDialog.saving" @click="saveView">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建函数 -->
    <el-dialog v-model="functionDialog.open" title="新建函数" width="680px" :close-on-click-modal="false" top="6vh"
      destroy-on-close>
      <el-form label-position="top" size="small">
        <el-form-item label="函数名" required>
          <el-input v-model="functionDialog.name" />
        </el-form-item>
        <el-form-item label="入参（inputParams）">
          <div class="pc-fields">
            <div v-for="(param, index) in functionDialog.inputParams" :key="index" class="pc-field-row">
              <el-input v-model="param.name" size="small" placeholder="参数名" class="pc-field-name" />
              <el-input v-model="param.type" size="small" placeholder="类型" class="pc-field-type" />
              <el-button size="small" text type="danger" @click="functionDialog.inputParams.splice(index, 1)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" text type="primary" @click="functionDialog.inputParams.push({ name: '', type: '' })">
              <el-icon><Plus /></el-icon>
              <span class="pc-field-add">添加参数</span>
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="返回参数（returnParams）">
          <div class="pc-fields">
            <div v-for="(param, index) in functionDialog.returnParams" :key="index" class="pc-field-row">
              <el-input v-model="param.name" size="small" placeholder="参数名" class="pc-field-name" />
              <el-input v-model="param.type" size="small" placeholder="类型" class="pc-field-type" />
              <el-button size="small" text type="danger" @click="functionDialog.returnParams.splice(index, 1)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" text type="primary" @click="functionDialog.returnParams.push({ name: '', type: '' })">
              <el-icon><Plus /></el-icon>
              <span class="pc-field-add">添加参数</span>
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="确定性的（deterministic）">
          <el-switch v-model="functionDialog.deterministic" />
        </el-form-item>
        <el-form-item required>
          <template #label>
            <FieldLabel label="函数定义（definitions）">
              键名是定义名，值形如 <code>{type, definition}</code>；
              <code>type</code> 取 file / sql / lambda。
            </FieldLabel>
          </template>
          <el-input
            v-model="functionDialog.definitions"
            type="textarea"
            :rows="7"
            class="pc-pre"
            placeholder='{"sql": {"type": "sql", "definition": "SELECT ..."}}'
          />
        </el-form-item>
        <el-form-item label="注释（comment）">
          <el-input v-model="functionDialog.comment" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="functionDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="functionDialog.saving" @click="saveFunction">创建</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detail.open" :title="detail.title" size="620px">
      <JsonBlock :value="detail.payload" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import { catalogApi } from '@/api/catalog.js'
import { describeError } from '@/api/client.js'
import { confirmDanger, notifyError, notifySuccess, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { prettyJson } from '@/utils/format.js'

/**
 * 目录浏览器：左侧数据库，右侧对象。
 *
 * <p>列表请求一律带 `maxResults`（取服务端 `max-page-size`），
 * 服务端仍可能回 `nextPageToken`——那时页面底部会出现「加载更多」，
 * 而不是把列表悄悄截断，让人以为对象就这么多。
 */

const PAIMON_TYPES = [
  'BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'FLOAT', 'DOUBLE',
  'DECIMAL(10, 2)', 'CHAR(10)', 'VARCHAR(64)', 'STRING', 'BINARY(16)', 'VARBINARY(64)', 'BYTES',
  'DATE', 'TIME', 'TIMESTAMP', 'TIMESTAMP_LTZ',
  'ARRAY<STRING>', 'MAP<STRING, STRING>', 'ROW<a STRING, b INT>',
]

const route = useRoute()
const router = useRouter()

const tab = ref('tables')
const dbFilter = ref('')
const tableFilter = ref('')

/** 当前 catalog：URL 上的 `catalog` 优先，其次全局选择。 */
const prefix = computed(() => route.query.catalog || session.state.selectedCatalog || '')
const database = computed(() => route.query.database || '')

const pageSize = computed(() => session.state.meta?.service?.maxPageSize || 1000)

// ------------------------------------------------------------------ 数据库

// 由下面的 watch 驱动首次加载，这里不再 immediate，
// 否则挂载时会与 watch 的首次回调各发一次请求。
const databaseResource = useResource(async () => {
  if (!prefix.value) return []
  const payload = await catalogApi.listDatabases(prefix.value, { maxResults: pageSize.value })
  return payload.databases || []
}, { immediate: false })

const databases = computed(() => databaseResource.data.value || [])
const dbLoading = computed(() => databaseResource.loading.value)
const dbError = computed(() => databaseResource.error.value)

const filteredDatabases = computed(() => {
  const keyword = dbFilter.value.trim().toLowerCase()
  if (!keyword) return databases.value
  return databases.value.filter((name) => name.toLowerCase().includes(keyword))
})

function reloadDatabases() {
  databaseResource.load()
  loadCurrentList()
}

function selectDatabase(name) {
  router.replace({ name: 'browse', query: { catalog: prefix.value, database: name } })
}

// ------------------------------------------------------------------ 对象列表

/**
 * 造一个「支持翻页的列表」。
 *
 * <p>各自维护 items / nextPageToken，与服务端的 `nextPageToken` 约定一一对应。
 * `reset = false` 时把新一页追加到已有结果后。
 */
function createPagedList(field, call) {
  const items = ref([])
  const token = ref('')
  const loading = ref(false)
  const error = ref('')
  let sequence = 0

  async function load(reset = true) {
    const current = ++sequence
    if (!prefix.value || !database.value) {
      items.value = []
      token.value = ''
      return
    }
    loading.value = true
    error.value = ''
    if (reset) {
      items.value = []
      token.value = ''
    }
    try {
      const payload = await call(prefix.value, database.value, {
        maxResults: pageSize.value,
        pageToken: token.value || undefined,
      })
      if (current !== sequence) return
      const page = payload[field] || []
      items.value = reset ? page : [...items.value, ...page]
      token.value = payload.nextPageToken || ''
    } catch (cause) {
      if (current === sequence) {
        error.value = describeError(cause)
      }
    } finally {
      if (current === sequence) {
        loading.value = false
      }
    }
  }

  return { items, token, loading, error, load }
}

const tablesList = createPagedList('tables', (p, d, q) => catalogApi.listTables(p, d, q))
const viewsList = createPagedList('views', (p, d, q) => catalogApi.listViews(p, d, q))
const functionsList = createPagedList('functions', (p, d, q) => catalogApi.listFunctions(p, d, q))
const semanticViewsList = createPagedList('semanticViews', (p, d, q) => catalogApi.listSemanticViews(p, d, q))

const lists = {
  tables: tablesList,
  views: viewsList,
  functions: functionsList,
  semanticViews: semanticViewsList,
}

const currentList = computed(() => lists[tab.value] || tablesList)

const emptyText = computed(() => ({
  tables: '这个数据库里还没有表',
  views: '这个数据库里还没有视图',
  functions: '这个数据库里还没有函数',
  semanticViews: '这个数据库里还没有语义视图',
}[tab.value] || '暂无数据'))

const filteredTables = computed(() => {
  const keyword = tableFilter.value.trim().toLowerCase()
  if (!keyword) return tablesList.items.value
  return tablesList.items.value.filter((name) => name.toLowerCase().includes(keyword))
})

function loadCurrentList() {
  currentList.value.load(true)
}

watch([prefix, database], ([nextPrefix]) => {
  if (nextPrefix && nextPrefix !== session.state.selectedCatalog) {
    session.selectCatalog(nextPrefix)
  }
  databaseResource.load()
  loadCurrentList()
}, { immediate: true })

// 数据库列表到手后，若 URL 上没有指定库，自动落到第一个：
// 打开「目录浏览」时右侧直接有内容，不用先点一下。
watch(databases, (list) => {
  if (!database.value && list.length) {
    router.replace({ name: 'browse', query: { catalog: prefix.value, database: list[0] } })
  }
})

watch(tab, () => {
  // 首次切到某个页签时才拉数据，避免打开页面就发四个请求
  if (!currentList.value.items.value.length) {
    currentList.value.load(true)
  }
})

// ------------------------------------------------------------------ 新建数据库

const dbDialog = reactive({ open: false, name: '', options: {}, saving: false })

function openCreateDatabase() {
  dbDialog.name = ''
  dbDialog.options = {}
  dbDialog.open = true
}

async function saveDatabase() {
  if (!dbDialog.name.trim()) {
    notifyError(new Error('请填写数据库名称'))
    return
  }
  dbDialog.saving = true
  try {
    await catalogApi.createDatabase(prefix.value, {
      name: dbDialog.name.trim(),
      options: dbDialog.options,
    })
    notifySuccess(`已创建数据库 ${dbDialog.name.trim()}`)
    dbDialog.open = false
    databaseResource.load()
  } catch (error) {
    notifyError(error)
  } finally {
    dbDialog.saving = false
  }
}

async function confirmDropDatabase(name) {
  const ok = await confirmDanger({
    title: '删除数据库',
    target: `确认删除数据库「${name}」？`,
    detail: '数据库下的表会一并从元数据中移除。catalog API 的 drop 不会删除底层数据文件，'
      + '但这些文件将不再被任何 catalog 引用。',
  })
  if (!ok) return
  try {
    await catalogApi.dropDatabase(prefix.value, name)
    notifySuccess(`已删除数据库 ${name}`)
    if (database.value === name) {
      router.replace({ name: 'browse', query: { catalog: prefix.value } })
    }
    databaseResource.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 表

const tableDialog = reactive({
  open: false,
  name: '',
  fields: [],
  partitionKeys: [],
  primaryKeys: [],
  comment: '',
  options: {},
  saving: false,
})

/**
 * 新建表表单里「分区键」与「主键」两个下拉的可选值。
 *
 * <p>两者都只能取**已填写的字段名**——Paimon 不允许分区键或主键指向不存在的字段，
 * 而这两个数组在协议里是纯字符串列表，服务端无法替前端猜出候选。所以候选只能是
 * 当前这份字段表，随用户增删改字段实时重算。
 *
 * <p>去重是必要的：字段列表在编辑过程中允许暂时重名（用户还没改完），
 * 而重名的 option 会让 `filterable` 的匹配结果出现重复条目。
 */
const fieldNames = computed(() => {
  const seen = new Set()
  const names = []
  for (const field of tableDialog.fields) {
    const name = field.name?.trim()
    if (name && !seen.has(name)) {
      seen.add(name)
      names.push(name)
    }
  }
  return names
})

/**
 * 删除一个字段行，并把已选的分区键/主键里指向该字段的值一并去掉。
 *
 * <p>不清理的话，删掉字段后下拉里会留下一个选项列表中已不存在的标签：界面上看着
 * 像是还有这个分区键，提交时却只能由 Paimon 报错，或者更糟——静默发出一个引用
 * 不存在字段的 schema。在这里同步，界面与将要提交的内容才自洽。
 */
function removeField(index) {
  const removed = tableDialog.fields[index]?.name?.trim()
  tableDialog.fields.splice(index, 1)
  if (!removed || fieldNames.value.includes(removed)) {
    return
  }
  tableDialog.partitionKeys = tableDialog.partitionKeys.filter((key) => key !== removed)
  tableDialog.primaryKeys = tableDialog.primaryKeys.filter((key) => key !== removed)
}

function openCreateTable() {
  tableDialog.name = ''
  tableDialog.fields = [
    { name: 'id', type: 'BIGINT', description: '' },
    { name: 'data', type: 'STRING', description: '' },
  ]
  tableDialog.partitionKeys = []
  tableDialog.primaryKeys = []
  tableDialog.comment = ''
  tableDialog.options = {}
  tableDialog.open = true
}

async function saveTable() {
  const name = tableDialog.name.trim()
  if (!name) {
    notifyError(new Error('请填写表名'))
    return
  }
  const fields = tableDialog.fields
    .filter((field) => field.name.trim() && field.type.trim())
    .map((field, index) => ({
      id: index + 1,
      name: field.name.trim(),
      type: field.type.trim(),
      description: field.description?.trim() || null,
    }))
  if (!fields.length) {
    notifyError(new Error('至少填写一个字段（字段名与类型都不能为空）'))
    return
  }
  // 分区键与主键都是字段名的字符串列表，服务端只做透传，真正的约束由 Paimon 施加：
  // ① 两者都必须指向已声明的字段；② 主键非空时，分区键必须是主键的子集。
  // 在这里提前拦下，用户看到的是「哪几个字段不对」，而不是 Paimon 抛出的一行英文。
  const declared = new Set(fields.map((field) => field.name))
  const strayKeys = [
    ...new Set(
      [...tableDialog.partitionKeys, ...tableDialog.primaryKeys]
        .filter((key) => !declared.has(key)),
    ),
  ]
  if (strayKeys.length) {
    notifyError(
      new Error(`分区键与主键只能取已填写完整的字段（字段名与类型都不能为空），请修正：${strayKeys.join(', ')}`),
    )
    return
  }
  const outsidePrimary = tableDialog.primaryKeys.length
    ? tableDialog.partitionKeys.filter((key) => !tableDialog.primaryKeys.includes(key))
    : []
  if (outsidePrimary.length) {
    notifyError(
      new Error(`Paimon 要求分区键是主键的一部分，请把这些字段也加进主键：${outsidePrimary.join(', ')}`),
    )
    return
  }
  tableDialog.saving = true
  try {
    await catalogApi.createTable(prefix.value, database.value, {
      identifier: { database: database.value, object: name },
      schema: {
        fields,
        partitionKeys: tableDialog.partitionKeys,
        primaryKeys: tableDialog.primaryKeys,
        options: tableDialog.options,
        comment: tableDialog.comment || null,
      },
    })
    notifySuccess(`已创建表 ${name}`)
    tableDialog.open = false
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    tableDialog.saving = false
  }
}

const registerDialog = reactive({ open: false, name: '', path: '', saving: false })

/**
 * 按表 ID 定位。
 *
 * <p>存在的理由是排查场景：日志里只有一个表 ID，而 `/tables/id/{tableId}` 能绕过
 * 「先知道库再知道表」的层级直接反查。定位成功后跳到表详情页——
 * 控制台里对表的操作全在那一页。
 */
const locateDialog = reactive({ open: false, tableId: '', saving: false })

function openLocateTable() {
  locateDialog.tableId = ''
  locateDialog.open = true
}

async function locateTable() {
  const tableId = locateDialog.tableId.trim()
  if (!tableId) {
    notifyError(new Error('请填写表 ID'))
    return
  }
  locateDialog.saving = true
  try {
    const payload = await catalogApi.getTableById(prefix.value, tableId)
    locateDialog.open = false
    if (!payload?.database || !payload?.name) {
      notifyError(new Error('服务端没有返回该表所属的库与表名，无法跳转'))
      return
    }
    router.push({
      name: 'table',
      params: { prefix: prefix.value, database: payload.database, table: payload.name },
    })
  } catch (error) {
    notifyError(error)
  } finally {
    locateDialog.saving = false
  }
}

function openRegisterTable() {
  registerDialog.name = ''
  registerDialog.path = ''
  registerDialog.open = true
}

async function saveRegister() {
  const name = registerDialog.name.trim()
  const path = registerDialog.path.trim()
  if (!name || !path) {
    notifyError(new Error('表名与路径都不能为空'))
    return
  }
  registerDialog.saving = true
  try {
    await catalogApi.registerTable(prefix.value, database.value, {
      identifier: { database: database.value, object: name },
      path,
    })
    notifySuccess(`已注册表 ${name}`)
    registerDialog.open = false
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    registerDialog.saving = false
  }
}

async function confirmDropTable(name) {
  const ok = await confirmDanger({
    title: '删除表',
    target: `确认删除表「${name}」？`,
    detail: '元数据与快照历史会被移除。catalog API 的 drop 不删除数据文件，'
      + '需要回收磁盘空间时请另外清理仓库目录。',
  })
  if (!ok) return
  try {
    await catalogApi.dropTable(prefix.value, database.value, name)
    notifySuccess(`已删除表 ${name}`)
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 通用重命名

const renameDialog = reactive({ open: false, title: '', value: '', original: '', saving: false, apply: null })

function openRenameTable(table) {
  renameDialog.title = `重命名表：${table}`
  renameDialog.value = table
  renameDialog.original = table
  renameDialog.apply = async (next) => catalogApi.renameTable(
    prefix.value,
    { database: database.value, object: table },
    { database: database.value, object: next },
  )
  renameDialog.open = true
}

function openRenameView(view) {
  renameDialog.title = `重命名视图：${view}`
  renameDialog.value = view
  renameDialog.original = view
  renameDialog.apply = async (next) => catalogApi.renameView(
    prefix.value,
    { database: database.value, object: view },
    { database: database.value, object: next },
  )
  renameDialog.open = true
}

async function saveRename() {
  const next = renameDialog.value.trim()
  if (!next) {
    notifyError(new Error('新名称不能为空'))
    return
  }
  if (next === renameDialog.original) {
    // 名字没改：服务端会把「目标名已存在」判成 409，报出来的是一句对用户没有意义的
    // 「表已存在」。这里直接当作无操作，不发那趟请求，也不报错。
    renameDialog.open = false
    return
  }
  renameDialog.saving = true
  try {
    await renameDialog.apply(next)
    notifySuccess('已重命名')
    renameDialog.open = false
    tablesList.load(true)
    viewsList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    renameDialog.saving = false
  }
}

// ------------------------------------------------------------------ 视图

const viewDialog = reactive({ open: false, name: '', query: '', comment: '', saving: false })

function openCreateView() {
  viewDialog.name = ''
  viewDialog.query = ''
  viewDialog.comment = ''
  viewDialog.open = true
}

async function saveView() {
  const name = viewDialog.name.trim()
  if (!name || !viewDialog.query.trim()) {
    notifyError(new Error('视图名与查询语句都不能为空'))
    return
  }
  viewDialog.saving = true
  try {
    await catalogApi.createView(prefix.value, database.value, {
      identifier: { database: database.value, object: name },
      schema: {
        query: viewDialog.query,
        comment: viewDialog.comment || null,
      },
    })
    notifySuccess(`已创建视图 ${name}`)
    viewDialog.open = false
    viewsList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    viewDialog.saving = false
  }
}

async function confirmDropView(name) {
  const ok = await confirmDanger({
    title: '删除视图',
    target: `确认删除视图「${name}」？`,
    detail: '只删除视图定义，底表不受影响。',
  })
  if (!ok) return
  try {
    await catalogApi.dropView(prefix.value, database.value, name)
    notifySuccess(`已删除视图 ${name}`)
    viewsList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 函数

const functionDialog = reactive({
  open: false,
  name: '',
  inputParams: [],
  returnParams: [],
  deterministic: true,
  definitions: '',
  comment: '',
  saving: false,
})

const SQL_FUNCTION_EXAMPLE = '{\n  "sql": {\n    "type": "sql",\n    "definition": "SELECT $1 + $2"\n  }\n}'

function openCreateFunction() {
  functionDialog.name = ''
  functionDialog.inputParams = [{ name: 'a', type: 'INT' }, { name: 'b', type: 'INT' }]
  functionDialog.returnParams = [{ name: 'result', type: 'INT' }]
  functionDialog.deterministic = true
  functionDialog.definitions = SQL_FUNCTION_EXAMPLE
  functionDialog.comment = ''
  functionDialog.open = true
}

async function saveFunction() {
  const name = functionDialog.name.trim()
  if (!name) {
    notifyError(new Error('请填写函数名'))
    return
  }
  let definitions
  try {
    definitions = JSON.parse(functionDialog.definitions)
  } catch (cause) {
    notifyError(new Error(`函数定义不是合法 JSON：${cause.message}`))
    return
  }
  const toParams = (list) => list
    .filter((param) => param.name.trim() && param.type.trim())
    .map((param) => ({ name: param.name.trim(), type: param.type.trim() }))

  functionDialog.saving = true
  try {
    await catalogApi.createFunction(prefix.value, database.value, {
      name,
      inputParams: toParams(functionDialog.inputParams),
      returnParams: toParams(functionDialog.returnParams),
      deterministic: functionDialog.deterministic,
      definitions,
      comment: functionDialog.comment || null,
      options: {},
    })
    notifySuccess(`已创建函数 ${name}`)
    functionDialog.open = false
    functionsList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    functionDialog.saving = false
  }
}

async function confirmDropFunction(name) {
  const ok = await confirmDanger({
    title: '删除函数',
    target: `确认删除函数「${name}」？`,
    detail: '引用该函数的视图不会自动失效，需要自行检查。',
  })
  if (!ok) return
  try {
    await catalogApi.dropFunction(prefix.value, database.value, name)
    notifySuccess(`已删除函数 ${name}`)
    functionsList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 语义视图

async function showSemanticView(row) {
  try {
    const payload = await catalogApi.getSemanticView(prefix.value, database.value, row.name)
    detail.title = `语义视图：${row.name}`
    detail.payload = payload
    detail.open = true
  } catch (error) {
    notifyError(error)
  }
}

async function openUpsertSemanticView(row) {
  try {
    const payload = await catalogApi.getSemanticView(prefix.value, database.value, row.name)
    const format = payload.definition?.format || 'ossie-yaml'
    const content = window.prompt(`编辑语义视图「${row.name}」的定义内容`, payload.definition?.content || '')
    if (content === null) return
    await catalogApi.upsertSemanticView(prefix.value, database.value, row.name, {
      definition: { format, content },
    })
    notifySuccess('已保存')
    semanticViewsList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

async function confirmDropSemanticView(row) {
  const ok = await confirmDanger({
    title: '删除语义视图',
    target: `确认删除语义视图「${row.name}」？`,
    detail: '定义内容会被移除，且无法恢复。',
  })
  if (!ok) return
  try {
    await catalogApi.dropSemanticView(prefix.value, database.value, row.name)
    notifySuccess(`已删除 ${row.name}`)
    semanticViewsList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 详情

const detail = reactive({ open: false, title: '', payload: null })

async function showView(row) {
  try {
    const payload = await catalogApi.getView(prefix.value, database.value, row.name)
    detail.title = `视图：${row.name}`
    detail.payload = { ...payload, schema: { ...payload.schema, query: payload.schema?.query } }
    detail.open = true
  } catch (error) {
    notifyError(error)
  }
}

async function showFunction(row) {
  try {
    const payload = await catalogApi.getFunction(prefix.value, database.value, row.name)
    detail.title = `函数：${row.name}`
    detail.payload = { ...payload, definitionsPreview: prettyJson(payload.definitions) }
    detail.open = true
  } catch (error) {
    notifyError(error)
  }
}

defineExpose({ loadCurrentList })
</script>

<style scoped>

.pc-browse-body {
  display: grid;
  grid-template-columns: 250px 1fr;
  gap: 14px;
  align-items: start;
}

.pc-db-pane {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 190px);
  overflow: hidden;
}

.pc-pane-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--pc-border);
}

.pc-pane-title {
  font-size: 13px;
  font-weight: 600;
}

.pc-pane-tools {
  padding: 10px 12px 6px;
}

.pc-db-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 10px;
}

.pc-db-skeleton {
  padding: 8px;
}

.pc-db-item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12.5px;
}

.pc-db-item:hover {
  background: var(--pc-surface-2);
}

.pc-db-item.is-active {
  background: color-mix(in srgb, var(--el-color-primary) 12%, transparent);
  color: var(--el-color-primary);
  font-weight: 600;
}

.pc-db-icon {
  font-size: 13px;
  opacity: 0.8;
}

.pc-db-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pc-db-drop {
  opacity: 0;
  color: var(--el-color-danger);
  font-size: 13px;
}

.pc-db-item:hover .pc-db-drop {
  opacity: 0.75;
}

.pc-db-drop:hover {
  opacity: 1;
}

.pc-obj-pane {
  min-width: 0;
}

.pc-tabs {
  padding: 0 14px;
}

.pc-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.pc-obj-body {
  padding: 6px 14px 14px;
}

.pc-obj-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 13px;
}

.pc-filter {
  width: 200px;
}

.pc-more {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  font-size: 13px;
}

/* 字段编辑器 */

.pc-fields {
  width: 100%;
}

.pc-field-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.pc-field-name {
  flex: 0 0 26%;
}

.pc-field-type {
  flex: 0 0 32%;
}

.pc-field-desc {
  flex: 1;
}

.pc-field-add {
  margin-left: 4px;
}

.pc-pre :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

:deep(.el-divider__text) {
  font-size: 13px;
  color: var(--pc-text-dim);
  background: var(--pc-surface);
}
</style>
