# Catalog API 端点清单

Paimon Rest Catalog API 在本服务端的全部 **60 个端点**，按资源分组列出。

- 规格来源：Apache Paimon 的 `docs/static/rest-catalog-open-api.yaml`
  （随仓库副本 `spec/rest-catalog-open-api.yaml`，运行时也可从 `/rest-catalog-open-api.yaml` 下载）
- 规格规模：**60 个端点、144 个 schema**
- 基址：`{scheme}://{host}/v1`。除 `GET /v1/config` 外，全部端点都带 `{prefix}`——
  它是 catalog 的对外标识，由 `GET /v1/config` 下发
- 授权映射：`CatalogAccessRules` 把下面的路径映射到所需权限，
  **未登记映射的端点直接拒绝而不是放行**；测试从运行时请求映射枚举全部端点核对覆盖率，
  新增端点若忘记登记会让构建失败。详见 [`authorization.md`](authorization.md)

> 另有一个不在规格内的扩展端点 `POST /api/catalog/v1/oauth/tokens`
> （OAuth 2.0 客户端凭据换访问令牌，路径与 Polaris 对齐），见
> [`console-auth.md`](console-auth.md)。

## 配置（1）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/config` |

## database（5）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases` |
| POST | `/v1/{prefix}/databases` |
| GET | `/v1/{prefix}/databases/{database}` |
| POST | `/v1/{prefix}/databases/{database}` |
| DELETE | `/v1/{prefix}/databases/{database}` |

## 表与快照（18）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/tables` |
| POST | `/v1/{prefix}/databases/{database}/tables` |
| GET | `/v1/{prefix}/databases/{database}/table-details` |
| POST | `/v1/{prefix}/databases/{database}/register` |
| GET | `/v1/{prefix}/tables` |
| GET | `/v1/{prefix}/tables/id/{tableId}` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}` |
| DELETE | `/v1/{prefix}/databases/{database}/tables/{table}` |
| POST | `/v1/{prefix}/tables/rename` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/commit` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/rollback` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/rollback-schema` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/snapshot` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/snapshots` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/snapshots/{version}` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/token` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/auth` |

## 分区（6）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/partitions` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions/drop` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions/mark` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions/list-by-names` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions/list-by-filter` |

## 分支（5）与标签（4）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/branches` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/branches` |
| DELETE | `/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}/rename` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}/forward` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/tags` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/tags` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/tags/{tag}` |
| DELETE | `/v1/{prefix}/databases/{database}/tables/{table}/tags/{tag}` |

## 消费者（2）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/consumers` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/consumers/reset` |

## 视图（8）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/views` |
| POST | `/v1/{prefix}/databases/{database}/views` |
| GET | `/v1/{prefix}/databases/{database}/view-details` |
| GET | `/v1/{prefix}/views` |
| GET | `/v1/{prefix}/databases/{database}/views/{view}` |
| POST | `/v1/{prefix}/databases/{database}/views/{view}` |
| DELETE | `/v1/{prefix}/databases/{database}/views/{view}` |
| POST | `/v1/{prefix}/views/rename` |

## 函数（7）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/functions` |
| POST | `/v1/{prefix}/databases/{database}/functions` |
| GET | `/v1/{prefix}/databases/{database}/function-details` |
| GET | `/v1/{prefix}/functions` |
| GET | `/v1/{prefix}/databases/{database}/functions/{function}` |
| POST | `/v1/{prefix}/databases/{database}/functions/{function}` |
| DELETE | `/v1/{prefix}/databases/{database}/functions/{function}` |

## 语义视图（4，实验性）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/semantic-views` |
| GET | `/v1/{prefix}/databases/{database}/semantic-views/{semanticView}` |
| POST | `/v1/{prefix}/databases/{database}/semantic-views/{semanticView}` |
| DELETE | `/v1/{prefix}/databases/{database}/semantic-views/{semanticView}` |

---

覆盖率不靠人工比对：`scripts/api-sweep.sh` 对运行中的实例逐个调用这 60 个 operation
（会创建并清理 database `sales`，因此要求目标实例为初始状态）。
本服务端只提供元数据、不做数据面写入，`commit` 之类的边界见
[`../README.md`](../README.md) 第 6 节「实现说明与已知边界」。
