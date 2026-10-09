/**
 * 控制台调用的**全部**服务端端点，集中在这一处声明。
 *
 * <p>为什么不把路径散在各个视图里：控制台是用字符串拼 URL 的，
 * 而服务端的路径属于契约的一部分（catalog API 见
 * `static/rest-catalog-open-api.yaml`，管理 API 见 `spec/polaris-management-service.yml`）。
 * 路径写错时前端的表现是 404 空列表，看不出是前端拼错了还是服务端改了，
 * 因此把它们收成一张表，交给 `scripts/verify-console.py` 与服务端路由逐条比对——
 * 前端引用了服务端不存在的路径，或者服务端路由改名后前端没跟上，都能在离线状态下查出来。
 *
 * <p>`path` 用 `{名字}` 占位，由 `client.js` 做替换并做 URL 编码。
 */
export const ENDPOINTS = {
  // ------------------------------------------------------------------ 控制台元数据与认证
  // 本工程扩展端点（非 Polaris 规格）。
  //
  // 后三条在服务端鉴权范围之外（`WebConfig` 用精确路径排除），这是刻意的：
  // 它们要回答的正是「怎么登录」，把它们放在鉴权之后会形成死循环。
  // 代价是它们能被匿名调用，因此暴露的内容按「对匿名访问者是否敏感」筛过一遍，
  // 且两条换令牌的端点自带失败限速。
  meta: { method: 'GET', path: '/api/console/v1/meta' },
  // 登录引导：服务端支持哪些认证方式、令牌端点在哪儿、手上这个令牌算不算数。
  auth: { method: 'GET', path: '/api/console/v1/auth' },
  // 用户名 + 密码换令牌：控制台的默认方式，账号在服务端配置里（默认 admin/admin）。
  // 响应是本工程的驼峰字段（`accessToken` / `expiresInSeconds`），不是 OAuth 的 snake_case。
  passwordLogin: { method: 'POST', path: '/api/console/v1/login' },
  // 用 Polaris 的令牌端点路径，而不是本工程的 /api/console/：按 OAuth 2.0 写的客户端库、
  // 以及 Polaris 官方 console（默认 VITE_OAUTH_TOKEN_URL 就是这个地址）都能直接指向
  // 本服务端。响应字段也是 RFC 6749 的 snake_case，不走本工程其余 DTO 的驼峰。
  token: { method: 'POST', path: '/api/catalog/v1/oauth/tokens' },

  // ------------------------------------------------------------------ catalog API：服务发现
  config: { method: 'GET', path: '/v1/config' },

  // ------------------------------------------------------------------ catalog API：database
  listDatabases: { method: 'GET', path: '/v1/{prefix}/databases' },
  createDatabase: { method: 'POST', path: '/v1/{prefix}/databases' },
  getDatabase: { method: 'GET', path: '/v1/{prefix}/databases/{database}' },
  dropDatabase: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}' },
  alterDatabase: { method: 'POST', path: '/v1/{prefix}/databases/{database}' },

  // ------------------------------------------------------------------ catalog API：table
  listTables: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables' },
  listTableDetails: { method: 'GET', path: '/v1/{prefix}/databases/{database}/table-details' },
  listTablesGlobally: { method: 'GET', path: '/v1/{prefix}/tables' },
  createTable: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables' },
  registerTable: { method: 'POST', path: '/v1/{prefix}/databases/{database}/register' },
  getTable: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}' },
  getTableById: { method: 'GET', path: '/v1/{prefix}/tables/id/{tableId}' },
  alterTable: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}' },
  dropTable: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/tables/{table}' },
  renameTable: { method: 'POST', path: '/v1/{prefix}/tables/rename' },
  rollbackTable: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/rollback' },
  // 有意**不**包含 commitTable（`.../tables/{table}/commit`）：
  // 它是写入方（Spark / Flink 的 writer）提交快照的接口，请求体里要带
  // manifest list 等由写入方在本地生成的文件路径。控制台拼不出一个真实的快照，
  // 提供一个这样的表单只会让人提交出一张指向不存在文件的表。
  // 控制台对快照的介入只做「读」与「回滚」两件事。
  tableSnapshot: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/snapshot' },
  listSnapshots: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/snapshots' },
  getSnapshotByVersion: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/snapshots/{version}' },
  tableToken: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/token' },
  tableAuth: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/auth' },

  // ------------------------------------------------------------------ catalog API：tag / branch / partition
  listTags: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/tags' },
  createTag: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/tags' },
  getTag: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/tags/{tag}' },
  dropTag: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/tables/{table}/tags/{tag}' },
  listBranches: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches' },
  createBranch: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches' },
  dropBranch: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}' },
  renameBranch: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}/rename' },
  forwardBranch: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}/forward' },
  listPartitions: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/partitions' },

  // ------------------------------------------------------------------ catalog API：view
  listViews: { method: 'GET', path: '/v1/{prefix}/databases/{database}/views' },
  createView: { method: 'POST', path: '/v1/{prefix}/databases/{database}/views' },
  getView: { method: 'GET', path: '/v1/{prefix}/databases/{database}/views/{view}' },
  dropView: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/views/{view}' },
  renameView: { method: 'POST', path: '/v1/{prefix}/views/rename' },

  // ------------------------------------------------------------------ catalog API：function
  listFunctions: { method: 'GET', path: '/v1/{prefix}/databases/{database}/functions' },
  createFunction: { method: 'POST', path: '/v1/{prefix}/databases/{database}/functions' },
  getFunction: { method: 'GET', path: '/v1/{prefix}/databases/{database}/functions/{function}' },
  dropFunction: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/functions/{function}' },

  // ------------------------------------------------------------------ catalog API：semantic view
  listSemanticViews: { method: 'GET', path: '/v1/{prefix}/databases/{database}/semantic-views' },
  getSemanticView: { method: 'GET', path: '/v1/{prefix}/databases/{database}/semantic-views/{semanticView}' },
  upsertSemanticView: { method: 'POST', path: '/v1/{prefix}/databases/{database}/semantic-views/{semanticView}' },
  dropSemanticView: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/semantic-views/{semanticView}' },

  // ------------------------------------------------------------------ 管理 API：catalog
  listCatalogs: { method: 'GET', path: '/api/management/v1/catalogs' },
  createCatalog: { method: 'POST', path: '/api/management/v1/catalogs' },
  getCatalog: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}' },
  updateCatalog: { method: 'PUT', path: '/api/management/v1/catalogs/{catalogName}' },
  deleteCatalog: { method: 'DELETE', path: '/api/management/v1/catalogs/{catalogName}' },

  // ------------------------------------------------------------------ 管理 API：principal
  listPrincipals: { method: 'GET', path: '/api/management/v1/principals' },
  createPrincipal: { method: 'POST', path: '/api/management/v1/principals' },
  getPrincipal: { method: 'GET', path: '/api/management/v1/principals/{principalName}' },
  updatePrincipal: { method: 'PUT', path: '/api/management/v1/principals/{principalName}' },
  deletePrincipal: { method: 'DELETE', path: '/api/management/v1/principals/{principalName}' },
  rotatePrincipal: { method: 'POST', path: '/api/management/v1/principals/{principalName}/rotate' },
  resetPrincipal: { method: 'POST', path: '/api/management/v1/principals/{principalName}/reset' },
  listPrincipalRolesOfPrincipal: { method: 'GET', path: '/api/management/v1/principals/{principalName}/principal-roles' },
  grantPrincipalRole: { method: 'PUT', path: '/api/management/v1/principals/{principalName}/principal-roles' },
  revokePrincipalRole: { method: 'DELETE', path: '/api/management/v1/principals/{principalName}/principal-roles/{principalRoleName}' },

  // ------------------------------------------------------------------ 管理 API：principal role
  listPrincipalRoles: { method: 'GET', path: '/api/management/v1/principal-roles' },
  createPrincipalRole: { method: 'POST', path: '/api/management/v1/principal-roles' },
  getPrincipalRole: { method: 'GET', path: '/api/management/v1/principal-roles/{principalRoleName}' },
  updatePrincipalRole: { method: 'PUT', path: '/api/management/v1/principal-roles/{principalRoleName}' },
  deletePrincipalRole: { method: 'DELETE', path: '/api/management/v1/principal-roles/{principalRoleName}' },
  listPrincipalsOfPrincipalRole: { method: 'GET', path: '/api/management/v1/principal-roles/{principalRoleName}/principals' },
  listCatalogRolesOfPrincipalRole: { method: 'GET', path: '/api/management/v1/principal-roles/{principalRoleName}/catalog-roles/{catalogName}' },
  grantCatalogRole: { method: 'PUT', path: '/api/management/v1/principal-roles/{principalRoleName}/catalog-roles/{catalogName}' },
  revokeCatalogRole: { method: 'DELETE', path: '/api/management/v1/principal-roles/{principalRoleName}/catalog-roles/{catalogName}/{catalogRoleName}' },

  // ------------------------------------------------------------------ 管理 API：catalog role 与授权
  listCatalogRoles: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles' },
  createCatalogRole: { method: 'POST', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles' },
  getCatalogRole: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles/{catalogRoleName}' },
  updateCatalogRole: { method: 'PUT', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles/{catalogRoleName}' },
  deleteCatalogRole: { method: 'DELETE', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles/{catalogRoleName}' },
  listPrincipalRolesOfCatalogRole: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/principal-roles' },
  listGrants: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants' },
  addGrant: { method: 'PUT', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants' },
  revokeGrant: { method: 'POST', path: '/api/management/v1/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants' },
}

/** 端点名 → `METHOD path`，供校验脚本与调试输出复用。 */
export function describe(name) {
  const endpoint = ENDPOINTS[name]
  if (!endpoint) throw new Error(`unknown endpoint: ${name}`)
  return `${endpoint.method} ${endpoint.path}`
}
