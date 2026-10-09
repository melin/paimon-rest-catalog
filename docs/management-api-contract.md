# Polaris Management API 契约基线

本文档由 `spec/polaris-management-service.yml` 直接生成，用于实现与验收对照，
表格内容与规格逐字一致，不含人工推断。生成方式见 `scripts/gen-management-contract.py`。
§4.5 末尾有一处标为「实现侧说明」的段落，是本文件唯一的非规格内容。

- 规格来源：Apache Polaris `spec/polaris-management-service.yml`（`main` 分支）
- 规模：**17 个路径、33 个 operation、56 个 schema**
- 服务基址：`{scheme}://{host}/api/management/v1`（规格 `servers[0].url`）

> 说明：规格将管理服务与 catalog 服务分成两份文档，但由同一进程承载。
> 本实现据此在同一进程内同时暴露 `/api/management/v1/**`（本文档）与 `/v1/**`（Paimon Rest Catalog）。

## 1. 端点清单

| # | 方法 | 路径 | 请求体 | 主要响应 |
| --- | --- | --- | --- | --- |
| 1 | GET | `/catalogs` | — | `200` Catalogs |
| 2 | POST | `/catalogs` | `CreateCatalogRequest` | `201` Catalog, `404`, `409` |
| 3 | GET | `/catalogs/{catalogName}` | — | `200` Catalog, `404` |
| 4 | PUT | `/catalogs/{catalogName}` | `UpdateCatalogRequest` | `200` Catalog, `404`, `409` |
| 5 | DELETE | `/catalogs/{catalogName}` | — | `204`, `404` |
| 6 | GET | `/principals` | — | `200` Principals, `404` |
| 7 | POST | `/principals` | `CreatePrincipalRequest` | `201` PrincipalWithCredentials |
| 8 | GET | `/principals/{principalName}` | — | `200` Principal, `404` |
| 9 | PUT | `/principals/{principalName}` | `UpdatePrincipalRequest` | `200` Principal, `404`, `409` |
| 10 | DELETE | `/principals/{principalName}` | — | `204`, `404` |
| 11 | POST | `/principals/{principalName}/rotate` | — | `200` PrincipalWithCredentials, `404` |
| 12 | POST | `/principals/{principalName}/reset` | `ResetPrincipalRequest` | `200` PrincipalWithCredentials, `404` |
| 13 | GET | `/principals/{principalName}/principal-roles` | — | `200` PrincipalRoles, `404` |
| 14 | PUT | `/principals/{principalName}/principal-roles` | `GrantPrincipalRoleRequest` | `201`, `404` |
| 15 | DELETE | `/principals/{principalName}/principal-roles/{principalRoleName}` | — | `204`, `404` |
| 16 | GET | `/principal-roles` | — | `200` PrincipalRoles, `404` |
| 17 | POST | `/principal-roles` | `CreatePrincipalRoleRequest` | `201` PrincipalRole |
| 18 | GET | `/principal-roles/{principalRoleName}` | — | `200` PrincipalRole, `404` |
| 19 | PUT | `/principal-roles/{principalRoleName}` | `UpdatePrincipalRoleRequest` | `200` PrincipalRole, `404`, `409` |
| 20 | DELETE | `/principal-roles/{principalRoleName}` | — | `204`, `404` |
| 21 | GET | `/principal-roles/{principalRoleName}/principals` | — | `200` Principals, `404` |
| 22 | GET | `/principal-roles/{principalRoleName}/catalog-roles/{catalogName}` | — | `200` CatalogRoles, `404` |
| 23 | PUT | `/principal-roles/{principalRoleName}/catalog-roles/{catalogName}` | `GrantCatalogRoleRequest` | `201` |
| 24 | DELETE | `/principal-roles/{principalRoleName}/catalog-roles/{catalogName}/{catalogRoleName}` | — | `204`, `404` |
| 25 | GET | `/catalogs/{catalogName}/catalog-roles` | — | `200` CatalogRoles |
| 26 | POST | `/catalogs/{catalogName}/catalog-roles` | `CreateCatalogRoleRequest` | `201` CatalogRole, `404` |
| 27 | GET | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}` | — | `200` CatalogRole, `404` |
| 28 | PUT | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}` | `UpdateCatalogRoleRequest` | `200` CatalogRole, `404`, `409` |
| 29 | DELETE | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}` | — | `204`, `404` |
| 30 | GET | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/principal-roles` | — | `200` PrincipalRoles, `404` |
| 31 | GET | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` | — | `200` GrantResources |
| 32 | PUT | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` | `AddGrantRequest` | `201`, `404` |
| 33 | POST | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` | `RevokeGrantRequest` | `201`, `404` |

31/33 个 operation 声明了 `403`（无权限）响应；上表为节省篇幅略去该列。

未声明 `403` 的是 GET `/catalogs/{catalogName}/catalog-roles`、GET `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants`——两个只读端点，规格只给出了 `200`。
这属于规格侧遗漏，实现侧仍照常鉴权（见 §4.5）。

## 2. 权限枚举

权限按资源层级分组。同名权限在多个枚举中重复出现，表示它在该层级同样有效
（例如 `CATALOG_MANAGE_ACCESS` 在五组中都有，因此可在任一层级授予）。

### Catalog 级

`CatalogPrivilege`，58 项：

- `SEMANTIC_MODEL_LIST`、`SEMANTIC_MODEL_CREATE`、`SEMANTIC_MODEL_READ`、`SEMANTIC_MODEL_WRITE`
- `SEMANTIC_MODEL_DROP`、`SEMANTIC_MODEL_FULL_METADATA`、`SEMANTIC_MODEL_MANAGE_GRANTS_ON_SECURABLE`、`CATALOG_MANAGE_ACCESS`
- `CATALOG_MANAGE_CONTENT`、`CATALOG_MANAGE_METADATA`、`CATALOG_READ_PROPERTIES`、`CATALOG_WRITE_PROPERTIES`
- `NAMESPACE_CREATE`、`TABLE_CREATE`、`VIEW_CREATE`、`NAMESPACE_DROP`
- `TABLE_DROP`、`VIEW_DROP`、`NAMESPACE_LIST`、`TABLE_LIST`
- `VIEW_LIST`、`NAMESPACE_READ_PROPERTIES`、`TABLE_READ_PROPERTIES`、`VIEW_READ_PROPERTIES`
- `NAMESPACE_WRITE_PROPERTIES`、`TABLE_WRITE_PROPERTIES`、`VIEW_WRITE_PROPERTIES`、`TABLE_READ_DATA`
- `TABLE_WRITE_DATA`、`NAMESPACE_FULL_METADATA`、`TABLE_FULL_METADATA`、`VIEW_FULL_METADATA`
- `POLICY_CREATE`、`POLICY_WRITE`、`POLICY_READ`、`POLICY_DROP`
- `POLICY_LIST`、`POLICY_FULL_METADATA`、`CATALOG_ATTACH_POLICY`、`CATALOG_DETACH_POLICY`
- `TABLE_ASSIGN_UUID`、`TABLE_UPGRADE_FORMAT_VERSION`、`TABLE_ADD_SCHEMA`、`TABLE_SET_CURRENT_SCHEMA`
- `TABLE_ADD_PARTITION_SPEC`、`TABLE_ADD_SORT_ORDER`、`TABLE_SET_DEFAULT_SORT_ORDER`、`TABLE_ADD_SNAPSHOT`
- `TABLE_SET_SNAPSHOT_REF`、`TABLE_REMOVE_SNAPSHOTS`、`TABLE_REMOVE_SNAPSHOT_REF`、`TABLE_SET_LOCATION`
- `TABLE_SET_PROPERTIES`、`TABLE_REMOVE_PROPERTIES`、`TABLE_SET_STATISTICS`、`TABLE_REMOVE_STATISTICS`
- `TABLE_REMOVE_PARTITION_SPECS`、`TABLE_MANAGE_STRUCTURE`

### Namespace 级

`NamespacePrivilege`，56 项：

- `SEMANTIC_MODEL_LIST`、`SEMANTIC_MODEL_CREATE`、`SEMANTIC_MODEL_READ`、`SEMANTIC_MODEL_WRITE`
- `SEMANTIC_MODEL_DROP`、`SEMANTIC_MODEL_FULL_METADATA`、`SEMANTIC_MODEL_MANAGE_GRANTS_ON_SECURABLE`、`CATALOG_MANAGE_ACCESS`
- `CATALOG_MANAGE_CONTENT`、`CATALOG_MANAGE_METADATA`、`NAMESPACE_CREATE`、`TABLE_CREATE`
- `VIEW_CREATE`、`NAMESPACE_DROP`、`TABLE_DROP`、`VIEW_DROP`
- `NAMESPACE_LIST`、`TABLE_LIST`、`VIEW_LIST`、`NAMESPACE_READ_PROPERTIES`
- `TABLE_READ_PROPERTIES`、`VIEW_READ_PROPERTIES`、`NAMESPACE_WRITE_PROPERTIES`、`TABLE_WRITE_PROPERTIES`
- `VIEW_WRITE_PROPERTIES`、`TABLE_READ_DATA`、`TABLE_WRITE_DATA`、`NAMESPACE_FULL_METADATA`
- `TABLE_FULL_METADATA`、`VIEW_FULL_METADATA`、`POLICY_CREATE`、`POLICY_WRITE`
- `POLICY_READ`、`POLICY_DROP`、`POLICY_LIST`、`POLICY_FULL_METADATA`
- `NAMESPACE_ATTACH_POLICY`、`NAMESPACE_DETACH_POLICY`、`TABLE_ASSIGN_UUID`、`TABLE_UPGRADE_FORMAT_VERSION`
- `TABLE_ADD_SCHEMA`、`TABLE_SET_CURRENT_SCHEMA`、`TABLE_ADD_PARTITION_SPEC`、`TABLE_ADD_SORT_ORDER`
- `TABLE_SET_DEFAULT_SORT_ORDER`、`TABLE_ADD_SNAPSHOT`、`TABLE_SET_SNAPSHOT_REF`、`TABLE_REMOVE_SNAPSHOTS`
- `TABLE_REMOVE_SNAPSHOT_REF`、`TABLE_SET_LOCATION`、`TABLE_SET_PROPERTIES`、`TABLE_REMOVE_PROPERTIES`
- `TABLE_SET_STATISTICS`、`TABLE_REMOVE_STATISTICS`、`TABLE_REMOVE_PARTITION_SPECS`、`TABLE_MANAGE_STRUCTURE`

### Table 级

`TablePrivilege`，28 项：

- `CATALOG_MANAGE_ACCESS`、`TABLE_DROP`、`TABLE_LIST`、`TABLE_READ_PROPERTIES`
- `TABLE_WRITE_PROPERTIES`、`TABLE_READ_DATA`、`TABLE_WRITE_DATA`、`TABLE_FULL_METADATA`
- `TABLE_ATTACH_POLICY`、`TABLE_DETACH_POLICY`、`TABLE_ASSIGN_UUID`、`TABLE_UPGRADE_FORMAT_VERSION`
- `TABLE_ADD_SCHEMA`、`TABLE_SET_CURRENT_SCHEMA`、`TABLE_ADD_PARTITION_SPEC`、`TABLE_ADD_SORT_ORDER`
- `TABLE_SET_DEFAULT_SORT_ORDER`、`TABLE_ADD_SNAPSHOT`、`TABLE_SET_SNAPSHOT_REF`、`TABLE_REMOVE_SNAPSHOTS`
- `TABLE_REMOVE_SNAPSHOT_REF`、`TABLE_SET_LOCATION`、`TABLE_SET_PROPERTIES`、`TABLE_REMOVE_PROPERTIES`
- `TABLE_SET_STATISTICS`、`TABLE_REMOVE_STATISTICS`、`TABLE_REMOVE_PARTITION_SPECS`、`TABLE_MANAGE_STRUCTURE`

### View 级

`ViewPrivilege`，6 项：

- `CATALOG_MANAGE_ACCESS`、`VIEW_DROP`、`VIEW_LIST`、`VIEW_READ_PROPERTIES`
- `VIEW_WRITE_PROPERTIES`、`VIEW_FULL_METADATA`

### Policy 级

`PolicyPrivilege`，8 项：

- `CATALOG_MANAGE_ACCESS`、`POLICY_READ`、`POLICY_DROP`、`POLICY_WRITE`
- `POLICY_LIST`、`POLICY_FULL_METADATA`、`POLICY_ATTACH`、`POLICY_DETACH`

### Semantic model 级

`SemanticModelPrivilege`，6 项：

- `CATALOG_MANAGE_ACCESS`、`SEMANTIC_MODEL_READ`、`SEMANTIC_MODEL_WRITE`、`SEMANTIC_MODEL_DROP`
- `SEMANTIC_MODEL_FULL_METADATA`、`SEMANTIC_MODEL_MANAGE_GRANTS_ON_SECURABLE`

## 3. 资源模型

### 3.1 主体与角色

| schema | 父类 | 字段 |
| --- | --- | --- |
| `Principal` | — | `name` \*: string; `clientId`: string; `properties`: object; `createTimestamp`: integer; `lastUpdateTimestamp`: integer; `entityVersion`: integer |
| `PrincipalWithCredentials` | — | `principal` \*: `Principal`; `credentials` \*: object |
| `PrincipalRole` | — | `name` \*: string; `federated`: boolean; `properties`: object; `createTimestamp`: integer; `lastUpdateTimestamp`: integer; `entityVersion`: integer |
| `CatalogRole` | — | `name` \*: string; `properties`: object; `createTimestamp`: integer; `lastUpdateTimestamp`: integer; `entityVersion`: integer |

`\*` 表示 required。

### 3.2 请求体

| schema | 父类 | 字段 |
| --- | --- | --- |
| `CreateCatalogRequest` | — | `catalog` \*: `Catalog` |
| `UpdateCatalogRequest` | — | `currentEntityVersion`: integer; `properties`: object; `storageConfigInfo`: `StorageConfigInfo` |
| `CreatePrincipalRequest` | — | `principal`: `Principal`; `credentialRotationRequired`: boolean |
| `UpdatePrincipalRequest` | — | `currentEntityVersion` \*: integer; `properties` \*: object |
| `ResetPrincipalRequest` | — | `clientId`: string; `clientSecret`: string |
| `CreatePrincipalRoleRequest` | — | `principalRole`: `PrincipalRole` |
| `UpdatePrincipalRoleRequest` | — | `currentEntityVersion` \*: integer; `properties` \*: object |
| `GrantPrincipalRoleRequest` | — | `principalRole`: `PrincipalRole` |
| `CreateCatalogRoleRequest` | — | `catalogRole`: `CatalogRole` |
| `UpdateCatalogRoleRequest` | — | `currentEntityVersion` \*: integer; `properties` \*: object |
| `GrantCatalogRoleRequest` | — | `catalogRole`: `CatalogRole` |
| `AddGrantRequest` | — | `grant`: `GrantResource` |
| `RevokeGrantRequest` | — | `grant`: `GrantResource` |

### 3.3 授权载体 GrantResource

`GrantResource` 是以 `type` 为判别字段的多态联合，规格给出了显式 `discriminator.mapping`：

| `type` | 具体 schema | 定位字段 | 权限字段类型 |
| --- | --- | --- | --- |
| `catalog` | `CatalogGrant` | — | `CatalogPrivilege` |
| `namespace` | `NamespaceGrant` | `namespace`: array<string> | `NamespacePrivilege` |
| `table` | `TableGrant` | `namespace`: array<string>; `tableName`: string | `TablePrivilege` |
| `view` | `ViewGrant` | `namespace`: array<string>; `viewName`: string | `ViewPrivilege` |
| `policy` | `PolicyGrant` | `namespace`: array<string>; `policyName`: string | `PolicyPrivilege` |
| `semantic-model` | `SemanticModelGrant` | `namespace`: array<string>; `semanticModelName`: string | `SemanticModelPrivilege` |

各类别要求定位字段与权限字段同时存在（规格 `required`）；`type` 来自父类且为必填。

### 3.4 catalog 与存储配置

| schema | 父类 | 字段 |
| --- | --- | --- |
| `Catalog` | — | `type` \*: enum(`INTERNAL`, `EXTERNAL`); `name` \*: string; `properties` \*: object; `createTimestamp`: integer; `lastUpdateTimestamp`: integer; `entityVersion`: integer; `storageConfigInfo` \*: `StorageConfigInfo` |
| `PolarisCatalog` | `Catalog` | — |
| `ExternalCatalog` | `Catalog` | `connectionConfigInfo`: `ConnectionConfigInfo` |
| `StorageConfigInfo` | — | `storageType` \*: enum(`S3`, `GCS`, `AZURE`, `FILE`); `allowedLocations`: array<string>; `storageName`: string |
| `AwsStorageConfigInfo` | `StorageConfigInfo` | `roleArn`: string; `externalId`: string; `userArn`: string; `currentKmsKey`: string; `allowedKmsKeys`: array<string>; `encryptionKeys`: array<string>; `decryptionKeys`: array<string>; `region`: string; `endpoint`: string; `stsEndpoint`: string; `stsUnavailable`: boolean; `endpointInternal`: string; `pathStyleAccess`: boolean; `kmsUnavailable`: boolean |
| `AzureStorageConfigInfo` | `StorageConfigInfo` | `tenantId` \*: string; `multiTenantAppName`: string; `consentUrl`: string; `hierarchical`: boolean |
| `GcpStorageConfigInfo` | `StorageConfigInfo` | `gcsServiceAccount`: string |
| `HuaweiObsStorageConfigInfo` | `StorageConfigInfo` | `endpoint`: string; `stsUnavailable`: boolean — **非规格子类型，本工程扩展** |
| `AliyunOssStorageConfigInfo` | `StorageConfigInfo` | `endpoint`: string; `stsUnavailable`: boolean — **非规格子类型，本工程扩展** |
| `FileStorageConfigInfo` | `StorageConfigInfo` | — |
| `ConnectionConfigInfo` | — | `connectionType` \*: enum(`ICEBERG_REST`, `HADOOP`, `HIVE`, `BIGQUERY`); `uri`: string; `authenticationParameters`: `AuthenticationParameters`; `serviceIdentity`: `ServiceIdentityInfo`; `properties`: object |
| `AuthenticationParameters` | — | `authenticationType` \*: enum(`OAUTH`, `BEARER`, `SIGV4`, `IMPLICIT`, `GCP`) |

`StorageConfigInfo.storageType` 是存储实现的判别字段（`S3` / `GCS` / `AZURE` / `FILE`）；
`ConnectionConfigInfo.connectionType` 是外部连接实现的判别字段
（`ICEBERG_REST` / `HADOOP` / `HIVE` / `BIGQUERY`）。

上表 `StorageConfigInfo` 一行的 `enum` 是**规格原文**，只列规格声明的四个取值。
本实现另外支持 `OBS`（华为云）与 `OSS`（阿里云），它们是扩展而非规格内容，
因此不混进那一行——表格与规格保持可机械核对的一致，差异集中记在下方取舍里。

**六种存储配置均已实现**：`S3` / `AZURE` / `GCS` 的类型专属字段会原样保存并回读，
`AZURE` 的 `tenantId` 按其 `required` 校验（缺失返回 400）。
四点实现取舍：

- `AwsStorageConfigInfo` 中已废弃的 `currentKmsKey` / `allowedKmsKeys` 不接收也不返回，
  统一用 `encryptionKeys` / `decryptionKeys`；传入会被静默忽略。
- 落在本类型之外的字段（例如 `storageType` 为 `FILE` 却带 `roleArn`）同样被静默忽略，
  而不是报 400——全站都依赖 Spring 默认的宽松绑定，单独收紧会造成行为不一致。
- `PUT /catalogs/{catalogName}` 的 `storageConfigInfo` 是**整体替换**：
  请求里未出现的类型专属字段会被清空，不是「保持不变」。
- `OBS` / `OSS` 两个取值超出规格的 `enum`，是本工程为华为云 OBS 与阿里云 OSS 加的扩展。
  规格把这两家云归入「兼容 S3 协议的对象存储」，靠自定义 `endpoint` 接入；
  单列它们是为了让凭据下发产出厂商原生的 `fs.obs.*` / `fs.oss.*` 键族——这两套键与 `s3.*`
  互不通用，混用会静默失效。**影响面仅限管理 API 的按类型分派逻辑**：引擎只是把
  `storageConfigInfo` 原样透传，不解析 `storageType`，因此 REST Catalog 协议不受影响。
  两个子类型的 `endpoint` 都不是必填项：判断依据是引擎侧是否已在 `core-site.xml` 里
  配好对应端点，服务端无从得知，硬判会把合法配置拒之门外。

`ConnectionConfigInfo` 仍只按 Iceberg REST 形态处理。

## 4. 响应形状与状态码

本节同样由规格机械推导。单列一节的原因：这里是实现侧最容易踩空的地方——
**规格对单个资源的读取与更新直接返回裸对象，只有 principal 的创建、重置、轮换返回包装对象**。
多剥一层不会引发解析错误，只会静默地把 `entityVersion` 读成 `0`，
随后任何携带 `entityVersion` 的写操作都会因版本不匹配被拒。

### 4.1 成功响应逐 operation 对照

编号与 §1 一致：共 33 行，覆盖 33 个 operation 的全部 2xx 响应。

| # | 方法 | 路径 | 码 | 响应 schema | 形状 | 顶层字段 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | GET | `/catalogs` | `200` | `Catalogs` | 命名数组 | `catalogs`: array<`Catalog`> |
| 2 | POST | `/catalogs` | `201` | `Catalog` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 3 | GET | `/catalogs/{catalogName}` | `200` | `Catalog` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 4 | PUT | `/catalogs/{catalogName}` | `200` | `Catalog` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 5 | DELETE | `/catalogs/{catalogName}` | `204` | — | 空 | — |
| 6 | GET | `/principals` | `200` | `Principals` | 命名数组 | `principals`: array<`Principal`> |
| 7 | POST | `/principals` | `201` | `PrincipalWithCredentials` | 包装对象 | `principal`: `Principal`; `credentials`: object |
| 8 | GET | `/principals/{principalName}` | `200` | `Principal` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 9 | PUT | `/principals/{principalName}` | `200` | `Principal` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 10 | DELETE | `/principals/{principalName}` | `204` | — | 空 | — |
| 11 | POST | `/principals/{principalName}/rotate` | `200` | `PrincipalWithCredentials` | 包装对象 | `principal`: `Principal`; `credentials`: object |
| 12 | POST | `/principals/{principalName}/reset` | `200` | `PrincipalWithCredentials` | 包装对象 | `principal`: `Principal`; `credentials`: object |
| 13 | GET | `/principals/{principalName}/principal-roles` | `200` | `PrincipalRoles` | 命名数组 | `roles`: array<`PrincipalRole`> |
| 14 | PUT | `/principals/{principalName}/principal-roles` | `201` | — | 空 | — |
| 15 | DELETE | `/principals/{principalName}/principal-roles/{principalRoleName}` | `204` | — | 空 | — |
| 16 | GET | `/principal-roles` | `200` | `PrincipalRoles` | 命名数组 | `roles`: array<`PrincipalRole`> |
| 17 | POST | `/principal-roles` | `201` | `PrincipalRole` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 18 | GET | `/principal-roles/{principalRoleName}` | `200` | `PrincipalRole` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 19 | PUT | `/principal-roles/{principalRoleName}` | `200` | `PrincipalRole` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 20 | DELETE | `/principal-roles/{principalRoleName}` | `204` | — | 空 | — |
| 21 | GET | `/principal-roles/{principalRoleName}/principals` | `200` | `Principals` | 命名数组 | `principals`: array<`Principal`> |
| 22 | GET | `/principal-roles/{principalRoleName}/catalog-roles/{catalogName}` | `200` | `CatalogRoles` | 命名数组 | `roles`: array<`CatalogRole`> |
| 23 | PUT | `/principal-roles/{principalRoleName}/catalog-roles/{catalogName}` | `201` | — | 空 | — |
| 24 | DELETE | `/principal-roles/{principalRoleName}/catalog-roles/{catalogName}/{catalogRoleName}` | `204` | — | 空 | — |
| 25 | GET | `/catalogs/{catalogName}/catalog-roles` | `200` | `CatalogRoles` | 命名数组 | `roles`: array<`CatalogRole`> |
| 26 | POST | `/catalogs/{catalogName}/catalog-roles` | `201` | `CatalogRole` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 27 | GET | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}` | `200` | `CatalogRole` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 28 | PUT | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}` | `200` | `CatalogRole` | 裸对象 | 资源自身字段（见 §3.1 / §3.4） |
| 29 | DELETE | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}` | `204` | — | 空 | — |
| 30 | GET | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/principal-roles` | `200` | `PrincipalRoles` | 命名数组 | `roles`: array<`PrincipalRole`> |
| 31 | GET | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` | `200` | `GrantResources` | 命名数组 | `grants`: array<`GrantResource`> |
| 32 | PUT | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` | `201` | — | 空 | — |
| 33 | POST | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` | `201` | — | 空 | — |

形状判定规则（对本节全部行成立）：

- **空**：`204`，响应未声明 `content`。
- **命名数组**：顶层只有一个字段，且其类型是数组，字段名由规格指定。
- **裸对象**：顶层同时含 `name` 与 `entityVersion`，资源自身就是响应体。
- **包装对象**：顶层字段不含资源身份字段，需要再取一层才是资源。

### 4.2 包装对象只有 3 处

| 方法 | 路径 | 码 | 响应 schema | 顶层字段 |
| --- | --- | --- | --- | --- |
| POST | `/principals` | `201` | `PrincipalWithCredentials` | `principal`: `Principal`; `credentials`: object |
| POST | `/principals/{principalName}/rotate` | `200` | `PrincipalWithCredentials` | `principal`: `Principal`; `credentials`: object |
| POST | `/principals/{principalName}/reset` | `200` | `PrincipalWithCredentials` | `principal`: `Principal`; `credentials`: object |

`credentials` 在规格里是内联匿名对象 `{clientId, clientSecret}`，没有对应的具名 schema，
实现时按该形状直接映射即可，不必在规格里找一个叫 `PrincipalCredential` 的类型。

### 4.3 命名数组的容器字段

共 5 个列表响应，容器字段名由规格给定，且**并不统一**：

| 响应 schema | 容器字段 | 元素类型 | 元素 schema |
| --- | --- | --- | --- |
| `Catalogs` | `catalogs` | array<`Catalog`> | `Catalog` |
| `Principals` | `principals` | array<`Principal`> | `Principal` |
| `PrincipalRoles` | `roles` | array<`PrincipalRole`> | `PrincipalRole` |
| `CatalogRoles` | `roles` | array<`CatalogRole`> | `CatalogRole` |
| `GrantResources` | `grants` | array<`GrantResource`> | `GrantResource` |

注意 `roles` 被复用：同一个容器字段名下元素类型并不相同（`CatalogRole` 与 `PrincipalRole`）。
因此不能只按字段名反序列化，必须结合请求路径决定元素类型。

### 4.4 成功码：POST 不必然是 201

全部 33 个成功响应的码分布：

- `200`：19 次
- `201`：8 次
- `204`：6 次

| 方法 | 路径 | 成功码 | 响应 schema |
| --- | --- | --- | --- |
| POST | `/catalogs` | `201` | `Catalog` |
| POST | `/principals` | `201` | `PrincipalWithCredentials` |
| POST | `/principals/{principalName}/rotate` | `200` ← 非 201 | `PrincipalWithCredentials` |
| POST | `/principals/{principalName}/reset` | `200` ← 非 201 | `PrincipalWithCredentials` |
| POST | `/principal-roles` | `201` | `PrincipalRole` |
| POST | `/catalogs/{catalogName}/catalog-roles` | `201` | `CatalogRole` |
| POST | `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` | `201` | — |

上表 7 个 POST 里，2 个返回 `200`：`/principals/{principalName}/rotate`、`/principals/{principalName}/reset`。
其余 POST 返回 `201`。按「写操作一律 201」的惯例写客户端，会在这两个端点上把成功误判为失败。

### 4.5 错误码分布与规格遗漏

| 码 | 声明的 operation 数 | 含义 |
| --- | --- | --- |
| `403` | 31 / 33 | 调用者无权限 |
| `404` | 27 / 33 | 资源不存在 |
| `409` | 5 / 33 | 重名或 `entityVersion` 冲突 |

声明 `409` 的 5 个 operation：POST `/catalogs`、PUT `/catalogs/{catalogName}`、PUT `/principals/{principalName}`、PUT `/principal-roles/{principalRoleName}`、PUT `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}`。

`403` 未覆盖全部 operation：GET `/catalogs/{catalogName}/catalog-roles`、GET `/catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` 只声明了 `200`。
这两个端点同属「列出某个 catalog 下的角色」与「列出某个角色的授权」，
规格漏写了错误响应，不是有意开放。

**实现侧说明（非规格内容）**：本实现对上述两个端点与同组其余端点一致地要求
`CATALOG_MANAGE_ACCESS`，即按「规格未声明也鉴权」的失败关闭方向处理，
见 `CatalogRoleController` 与 `docs/authorization.md`。

