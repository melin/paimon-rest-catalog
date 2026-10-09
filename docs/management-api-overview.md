# Management API 概览

Apache Polaris 的 **REST Management API**（33 个 operation、17 条路径、56 个 schema），
由本服务端在同一进程内实现，基址 `http://localhost:8080/api/management/v1`——
与 Catalog API 由同一进程承载，只是一份规格把它拆成了两个服务。

本文件是**阅读地图**：说清 33 个 operation 落在哪几个控制器上、以及几处容易读错的语义。
逐字对照的端点清单、请求体形状、权限枚举、资源模型与逐 operation 的响应形状见
[`management-api-contract.md`](management-api-contract.md)（由规格生成，不在本文重复）。

| 想知道什么 | 看哪份 |
| --- | --- |
| 33 个 operation 分别属于谁、分成几类 | 本文 |
| 端点、字段、权限枚举、响应形状与状态码（逐字） | [`management-api-contract.md`](management-api-contract.md) |
| 权限怎么判定、实体链怎么写 | [`authorization.md`](authorization.md) |
| 用 SQL 而不是 HTTP 调这套接口 | [`spark-sql-reference.md`](spark-sql-reference.md) |

## 1. 控制器划分

按资源分成四组：

| 控制器 | operation | 覆盖 |
| --- | --- | --- |
| `PrincipalController` | 10 | 主体的增删改查、`reset` / `rotate` 凭据、主体 ↔ principal role 装配 |
| `PrincipalRoleController` | 9 | principal role 的增删改查、其下的主体与 catalog role 查询、catalog role 装配 |
| `CatalogRoleController` | 9 | catalog role 的增删改查、其下的 principal role 与资源授权 |
| `ManagementCatalogController` | 5 | 管理面的 catalog 列表、创建、查询、更新、删除 |

## 2. 关键语义

详细说明与设计理由见 [`authorization.md`](authorization.md)。

- **RBAC 链路**：主体 → principal role → catalog role → 资源授权。两级都是多对多，
  catalog role 归属于某个具体 catalog。
- **权限蕴含**由 `PrivilegeModel` 编码，规则逐条标注 Polaris 文档出处，文档未明说的不做推断；
  `*_FULL_METADATA` 按权限名前缀推导而非取规格的层级枚举全集，以避免权限提升。
- **乐观并发**：`PUT` 请求携带 `currentEntityVersion`，与当前版本不符时返回 `409`。
- **状态码**：创建与授予类 `201`，`PUT` 更新与 `reset` / `rotate` 为 `200`，删除为 `204`，
  无权限 `403`，不存在 `404`，版本冲突 `409`。
- **响应形状**：单体资源接口大多返回**裸对象**，只有主体相关接口返回
  `{"principal":{…},"credentials":{…}}`；列表接口是具名数组。这是最容易读错的一处，
  逐 operation 对照见 [`management-api-contract.md`](management-api-contract.md) 第 4 节，
  实测结论见 [`spark-sql-extension.md`](spark-sql-extension.md) 第 3.3 节。
- **规格遗漏**：`GET /catalogs/{name}/catalog-roles` 与
  `GET /catalogs/{name}/catalog-roles/{role}/grants` 在规格中只声明了 `200`，
  没有 `403` / `404`；本实现按失败关闭处理，仍要求 `CATALOG_MANAGE_ACCESS`。
- **存储配置支持六种类型**。`storageConfigInfo` 在规格里是按 `storageType` 判别的联合，
  因此 Java 侧也按子类型建模（`S3` / `AZURE` / `GCS` / `OBS` / `OSS` / `FILE` 各有自己的字段集合），
  非法组合在类型层面就不成立。`AZURE` 的 `tenantId` 按规格的 `required` 校验（缺失返回 400）；
  `S3` 的废弃字段 `currentKmsKey` / `allowedKmsKeys` 不接收也不返回。
  其中 `OBS`（华为云）与 `OSS`（阿里云）**不在 Polaris 规格的取值集合里**，是本工程的扩展：
  规格把这两家云归入「兼容 S3 协议的对象存储」，靠自定义 `endpoint` 接入，
  单列它们是为了让凭据下发能产出厂商原生的 `fs.obs.*` / `fs.oss.*` 键族。
  超出规格的只是 `storageType` 的取值集合，REST Catalog 协议不受影响——引擎只原样透传这个字段。
  落库时整份配置存进一列 JSON，`storage_type` 与 `allowed_locations_json` 是它的投影列，
  便于直接用 SQL 筛选。`allowedLocations` 的首项即该 catalog 的仓库位置，
  表路径由它推导——改存储会同时改新库表的位置。
  实现细节与实测结论见 [`management-api-contract.md`](management-api-contract.md) 第 3.4 节。

## 3. 覆盖率与契约校验

- `scripts/management-sweep.sh` 对着运行中的实例跑遍 33 个 operation。
- `scripts/verify-management-contract.py` 反解 `management-api-contract.md` 的表格，
  与 `spec/polaris-management-service.yml` 逐行比对——文档改错会直接失败。
- 生成与校验是两条独立路径（`gen-management-contract.py` 出文档，
  `verify-management-contract.py` 独立反解），避免「生成器和校验器同源」导致空跑。
