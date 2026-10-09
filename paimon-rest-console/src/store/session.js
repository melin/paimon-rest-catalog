import { reactive } from 'vue'

import { describeError } from '@/api/client.js'
import { managementApi, metaApi } from '@/api/management.js'

/**
 * 会话级状态：catalog 列表、当前选中的 catalog、服务端元数据。
 *
 * <p>用模块级的 `reactive` 而不是引入 Pinia：控制台只有这一处跨页面共享的状态，
 * 加一个状态管理库后，代码量不会更少，但会多一层「必须知道它怎么用」的门槛。
 *
 * <p>**「当前选中的 catalog」是控制台的核心上下文**。catalog API 的每条路径都带
 * `{prefix}`，而 prefix 就是 catalog 名，所以这个选择决定了下钻浏览时请求打向哪个目录。
 * 它持久化在 `localStorage`：刷新后仍停在上次看的地方，是运维时更省事的行为。
 */

const SELECTED_KEY = 'paimon.console.catalog'

const state = reactive({
  /** 管理 API 返回的 catalog 列表。 */
  catalogs: [],
  catalogsLoaded: false,
  catalogsError: '',
  /** 当前 catalog 名（即 catalog API 的 prefix）。 */
  selectedCatalog: localStorage.getItem(SELECTED_KEY) || '',
  /** 服务端元数据（枚举取值与服务配置摘要）。 */
  meta: null,
  metaError: '',
  loadingCatalogs: false,
  loadingMeta: false,
})

/**
 * 加载 catalog 列表。
 *
 * <p>开启授权后，非服务管理员只会看到自己至少持有一个 catalog role 的 catalog——
 * 这是服务端的可见性过滤，控制台不做二次判断（前端过滤挡不住任何东西）。
 */
async function loadCatalogs() {
  state.loadingCatalogs = true
  try {
    const payload = await managementApi.listCatalogs()
    state.catalogs = payload.catalogs || []
    state.catalogsError = ''
    state.catalogsLoaded = true
    const names = state.catalogs.map((catalog) => catalog.name)
    if (!state.selectedCatalog || !names.includes(state.selectedCatalog)) {
      // 选中的 catalog 已被删除时退回第一个，避免所有下钻页面都 404
      selectCatalog(names[0] || '')
    }
    return state.catalogs
  } catch (error) {
    state.catalogsError = describeError(error)
    state.catalogsLoaded = true
    throw error
  } finally {
    state.loadingCatalogs = false
  }
}

async function loadMeta() {
  state.loadingMeta = true
  try {
    state.meta = await metaApi.meta()
    state.metaError = ''
    return state.meta
  } catch (error) {
    state.metaError = describeError(error)
    return null
  } finally {
    state.loadingMeta = false
  }
}

function selectCatalog(name) {
  state.selectedCatalog = name || ''
  if (name) {
    localStorage.setItem(SELECTED_KEY, name)
  } else {
    localStorage.removeItem(SELECTED_KEY)
  }
}

/** 当前 catalog 的对象（含 storageConfigInfo），未选中时为 `null`。 */
function currentCatalog() {
  return state.catalogs.find((catalog) => catalog.name === state.selectedCatalog) || null
}

export const session = {
  state,
  loadCatalogs,
  loadMeta,
  selectCatalog,
  currentCatalog,
  /** 首屏加载：两个请求互不依赖，并发发出。 */
  bootstrap() {
    return Promise.allSettled([loadCatalogs(), loadMeta()])
  },
  reset() {
    state.catalogs = []
    state.catalogsLoaded = false
    state.catalogsError = ''
    state.meta = null
    state.metaError = ''
    selectCatalog('')
  },
}
