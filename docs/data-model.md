# 数据模型

服务端把 catalog 元数据与 RBAC 授权关系全部放进关系库，共 **18 张表、18 个 JPA 仓储接口**。
本文件说明表的划分，以及三个容易看错或踩坑的设计点：摘要列、审计列、JSON 文档列。

- 实体与仓储：`paimon-rest-server/src/main/java/io/github/melin/paimonrest/domain/entity/`
  （18 个实体，另含 `AuditedEntity` 基类与 `JsonConverters`）与同级的 `domain/repo/`（18 个仓储接口）
- 建表语句：[`../sql/schema-mysql.sql`](../sql/schema-mysql.sql)（由实体生成，改实体后必须重跑）
- 库结构由 `ddl-auto: validate` 守住：实体与库不一致时应用**直接启动失败**，
  代价是实体变更后要自行迁移存量数据（见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 14 条）

---

## 1. Catalog 侧（12 张）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `paimon_catalog` | catalog | prefix、warehouse、defaults / overrides |
| `paimon_database` | database | 命名空间、位置、属性 |
| `paimon_table` | table | 当前 schema、路径、类型、最新快照指针 |
| `paimon_table_schema` | schema 历史版本 | 支撑 `rollback-schema` |
| `paimon_snapshot` | snapshot / TableSnapshot | 清单指针 + 聚合统计 |
| `paimon_branch` | branch | 指向快照的命名历史线 |
| `paimon_tag` | tag | 快照的稳定别名与保留时长 |
| `paimon_partition` | partition | spec、统计、done 标记、选项 |
| `paimon_view` | view | 字段、查询语句、方言映射 |
| `paimon_function` | function | 入参/出参/定义体/属性 |
| `paimon_consumer` | consumer | 流式消费位点 |
| `paimon_semantic_view` | semantic view | 格式 + 全文定义 |

## 2. 管理侧（6 张，RBAC）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `paimon_principal` | principal | 主体；只保存密钥摘要，明文仅在创建 / 轮换时返回一次 |
| `paimon_principal_role` | principal role | 服务级角色，与 catalog 无关 |
| `paimon_catalog_role` | catalog role | 归属于某个 catalog 的角色 |
| `paimon_principal_role_grant` | 主体 ↔ principal role | 多对多关联 |
| `paimon_catalog_role_grant` | principal role ↔ catalog role | 多对多关联 |
| `paimon_resource_grant` | catalog role 的资源授权 | 权限取值 + 资源类型 + 命名空间 + 对象名 |

两级角色都是多对多，链路为「主体 → principal role → catalog role → 资源授权」。
判定逻辑与失败关闭的取舍见 [`authorization.md`](authorization.md)。

---

## 3. 为什么有两个摘要列

`paimon_partition.spec_hash` 与 `paimon_resource_grant.namespace_hash` 是各自「键」字段的
SHA-256 定长摘要，唯一约束落在摘要上而不是原文上。原因是 MySQL 的索引键上限是
**3072 字节**（utf8mb4 下 768 个字符）：

- `spec_key` 声明为 1024 字符 → 与 `table_id` 相加远超上限；
- `namespace_key` 是命名空间路径的拼接，而规格对层级数没有上限，长度本就无界；
- 这两者与 `catalog_role_id` / `object_name` 等列合起来更放不下。

直接建索引会被 MySQL 拒绝：

```
ERROR 1071 (42000): Specified key was too long; max key length is 3072 bytes
```

改法是「原文入库 + 摘要入索引」：`spec_key` / `namespace_key` 保留，供等值查询使用；
唯一约束改用定长 64 字符的摘要，索引长度与值的实际长度解耦，唯一性语义不变。
摘要在 `@PrePersist` / `@PreUpdate` 里由原文重算（见 `Digests`），因此不依赖调用方是否记得规范化。

## 4. 审计列：主体名超过 255 字符时记摘要

`owner` / `created_by` / `updated_by` 是 `varchar(255)`，而主体名的长度没有上界：
认证链认不出令牌时会退化为「令牌即主体名」，控制台签发的访问令牌就有 272 个字符，
于是写入以 `Data too long for column 'created_by'` 失败——现象是「建表/建库报 500」，
从错误信息里看不出与认证有关。

落库前统一在 `AuditedEntity` 归一化（`AuditPrincipal.of`）：放得下的原样存，
放不下的记 `sha256:<前 12 位>`。

- **不截断**：截断会把凭据的前 255 个字符原样写进元数据库，
  而元数据是能被列表接口读出来的。
- 超长时启动日志告警一次，给出的解法是配 `paimon.rest.auth.token-principals`
  把令牌映射成真正的名字。
- 归一化放在实体层而非 `RequestContext`：授权判定读的是**未归一化**的主体名，两者互不影响。
- **刻意不通过加宽这三列来解决**：它们会被接口原样返回，加宽等于让凭据明文入库并可被读回；
  且主体名长度没有上界，加宽只是把溢出推后，还要对 14 张表共 42 个列做迁移。

三列均无索引（全库只有主键索引），这一点对「加宽」有利，但不足以抵消上面的理由。
完整取舍见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 19 条。

## 5. 结构类字段以 JSON 存单列

schema、函数定义、分区 spec 等结构以 JSON 文本存进单列，实体字段保持强类型，
避免为每个属性建表；复杂结构通过 `AttributeConverter` 转换。
`storageConfigInfo` 同样整份存一列 JSON，`storage_type` 与
`allowed_locations_json` 是它的投影列，便于直接用 SQL 筛选。

这一列里唯一不是原样存储的是 catalog 自带静态凭据的 `secretAccessKey`：
写库前用 AES-GCM 加密成 `v1:<base64(iv ‖ 密文+tag)>` 再塞进这段 JSON，
因此直接查库看到的是密文，而接口层还会再抹一次（`StorageConfigs.withoutSecrets`）
——两处都是必要的，只做一处等于没做。加密密钥不落库，来自
`paimon.rest.storage.credential-secret-key`，所以**换密钥等于换密文**：
旧密文解不开会明确报 500，需要重新提交一次凭据
（见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 20 条）。
