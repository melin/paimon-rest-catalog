import { del, get, post } from './client.js'

/**
 * catalog API（`/v1/**`）的调用面。
 *
 * <p>规格见 `paimon-rest-server/src/main/resources/static/rest-catalog-open-api.yaml`。
 * 路径中的 `{prefix}` 就是 catalog 名——服务端的
 * `CatalogService.resolve(prefix)` 按 prefix 查 catalog，两侧是同一份数据。
 * 因此控制台里的「选中某个 catalog」等价于「后续调用都用它的名字作 prefix」。
 *
 * <p>这里的方法只做参数搬运，不发请求前不校验语义：
 * 哪些组合合法由服务端判定，控制台重复实现一遍判定只会两边漂移。
 */
export const catalogApi = {
  /** 目录发现。返回 `{defaults, overrides}`，`defaults.prefix` 即默认 catalog。 */
  config: (warehouse) => get('config', { params: { query: { warehouse } } }),

  // ------------------------------------------------------------------ database
  listDatabases: (prefix, query = {}) => get('listDatabases', { params: { prefix, query } }),
  createDatabase: (prefix, body) => post('createDatabase', { params: { prefix }, body }),
  getDatabase: (prefix, database) => get('getDatabase', { params: { prefix, database } }),
  dropDatabase: (prefix, database) => del('dropDatabase', { params: { prefix, database } }),
  /** `body` 形如 `{removals, updates}`。 */
  alterDatabase: (prefix, database, body) => post('alterDatabase', { params: { prefix, database }, body }),

  // ------------------------------------------------------------------ table
  listTables: (prefix, database, query = {}) => get('listTables', { params: { prefix, database, query } }),
  listTableDetails: (prefix, database, query = {}) => get('listTableDetails', { params: { prefix, database, query } }),
  listTablesGlobally: (prefix, query = {}) => get('listTablesGlobally', { params: { prefix, query } }),
  /** `body` 形如 `{identifier: {database, object}, schema: {...}}`。 */
  createTable: (prefix, database, body) => post('createTable', { params: { prefix, database }, body }),
  registerTable: (prefix, database, body) => post('registerTable', { params: { prefix, database }, body }),
  getTable: (prefix, database, table) => get('getTable', { params: { prefix, database, table } }),
  /** 按表 ID 反查表，用于「只知道 ID 要定位到某张表」的排查场景。 */
  getTableById: (prefix, tableId) => get('getTableById', { params: { prefix, tableId } }),
  /** `body` 形如 `{changes: [{action: 'setOption', ...}]}`。 */
  alterTable: (prefix, database, table, body) => post('alterTable', { params: { prefix, database, table }, body }),
  dropTable: (prefix, database, table) => del('dropTable', { params: { prefix, database, table } }),
  /**
   * 回滚到指定快照。
   *
   * <p>`instant` 是规格里的判别联合：`{type: 'snapshot', snapshotId}` 或
   * `{type: 'tag', tagName}`。服务端按 `type` 分派，缺省按 snapshot 处理。
   */
  rollbackTable: (prefix, database, table, instant) => post('rollbackTable', {
    params: { prefix, database, table },
    body: { instant },
  }),
  renameTable: (prefix, source, destination) => post('renameTable', {
    params: { prefix },
    body: { source, destination },
  }),
  tableSnapshot: (prefix, database, table) => get('tableSnapshot', { params: { prefix, database, table } }),
  listSnapshots: (prefix, database, table, query = {}) => get('listSnapshots', {
    params: { prefix, database, table, query },
  }),
  getSnapshotByVersion: (prefix, database, table, version) => get('getSnapshotByVersion', {
    params: { prefix, database, table, version },
  }),
  /** 下发数据访问令牌；`credential-manager.type=noop` 时服务端返回 501。 */
  tableToken: (prefix, database, table) => get('tableToken', { params: { prefix, database, table } }),
  /** 查询该主体在此表上的行过滤与列脱敏规则；`body` 形如 `{select: [...列名]}`。 */
  tableAuth: (prefix, database, table, body) => post('tableAuth', { params: { prefix, database, table }, body }),

  // ------------------------------------------------------------------ tag
  listTags: (prefix, database, table, query = {}) => get('listTags', { params: { prefix, database, table, query } }),
  createTag: (prefix, database, table, body) => post('createTag', { params: { prefix, database, table }, body }),
  getTag: (prefix, database, table, tag) => get('getTag', { params: { prefix, database, table, tag } }),
  dropTag: (prefix, database, table, tag) => del('dropTag', { params: { prefix, database, table, tag } }),

  // ------------------------------------------------------------------ branch
  listBranches: (prefix, database, table) => get('listBranches', { params: { prefix, database, table } }),
  createBranch: (prefix, database, table, body) => post('createBranch', { params: { prefix, database, table }, body }),
  dropBranch: (prefix, database, table, branch) => del('dropBranch', { params: { prefix, database, table, branch } }),
  renameBranch: (prefix, database, table, branch, body) => post('renameBranch', {
    params: { prefix, database, table, branch },
    body,
  }),
  forwardBranch: (prefix, database, table, branch, body) => post('forwardBranch', {
    params: { prefix, database, table, branch },
    body,
  }),

  // ------------------------------------------------------------------ partition
  listPartitions: (prefix, database, table, query = {}) => get('listPartitions', {
    params: { prefix, database, table, query },
  }),

  // ------------------------------------------------------------------ view
  listViews: (prefix, database, query = {}) => get('listViews', { params: { prefix, database, query } }),
  createView: (prefix, database, body) => post('createView', { params: { prefix, database }, body }),
  getView: (prefix, database, view) => get('getView', { params: { prefix, database, view } }),
  dropView: (prefix, database, view) => del('dropView', { params: { prefix, database, view } }),
  renameView: (prefix, source, destination) => post('renameView', {
    params: { prefix },
    body: { source, destination },
  }),

  // ------------------------------------------------------------------ function
  listFunctions: (prefix, database, query = {}) => get('listFunctions', { params: { prefix, database, query } }),
  createFunction: (prefix, database, body) => post('createFunction', { params: { prefix, database }, body }),
  getFunction: (prefix, database, fn) => get('getFunction', { params: { prefix, database, function: fn } }),
  dropFunction: (prefix, database, fn) => del('dropFunction', { params: { prefix, database, function: fn } }),

  // ------------------------------------------------------------------ semantic view
  listSemanticViews: (prefix, database, query = {}) => get('listSemanticViews', {
    params: { prefix, database, query },
  }),
  getSemanticView: (prefix, database, name) => get('getSemanticView', {
    params: { prefix, database, semanticView: name },
  }),
  upsertSemanticView: (prefix, database, name, body) => post('upsertSemanticView', {
    params: { prefix, database, semanticView: name },
    body,
  }),
  dropSemanticView: (prefix, database, name) => del('dropSemanticView', {
    params: { prefix, database, semanticView: name },
  }),
}
