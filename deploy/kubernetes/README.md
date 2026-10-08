# Kubernetes 部署

`deploy/kubernetes/` 下是一套自洽的清单：服务端 Deployment、MySQL StatefulSet、
warehouse 的持久卷、配置与凭据、Service / Ingress / PDB，用 kustomize 组装，
一条命令应用。

```
kubectl apply -k deploy/kubernetes
```

---

## 1. 前置条件

| 条件 | 说明 |
| --- | --- |
| Kubernetes | 1.24 及以上。清单用了 `policy/v1` 的 PodDisruptionBudget（1.21 GA）与 `networking.k8s.io/v1` 的 Ingress |
| 镜像 | 需要在能访问集群节点的机器上先构建，见第 2.2 节 |
| 存储 | 集群要有默认 StorageClass，否则 `paimon-warehouse` 与 MySQL 的 PVC 会一直 Pending |
| Ingress | 仅在需要从集群外访问时才要，没有的话把 ingress.yaml 从 kustomization.yaml 里去掉 |

---

## 2. 部署

### 2.1 替换凭据

`secret.yaml` 里是两个占位值，**必须先替换**。推荐不提交这个文件，改用命令行创建：

```bash
kubectl create namespace paimon-rest

kubectl -n paimon-rest create secret generic paimon-rest-credentials \
  --from-literal=DB_PASSWORD="$(openssl rand -base64 24)" \
  --from-literal=REST_TOKEN="$(openssl rand -hex 32)"
```

用这种方式创建时，把 `secret.yaml` 从 `kustomization.yaml` 的 `resources` 里去掉
（否则 apply 会用占位值把它覆盖回去）。

`REST_TOKEN` 是客户端调用时 `Authorization: Bearer <值>` 里的那个值。

### 2.2 构建并让节点拿到镜像

```bash
docker build -t paimon-rest-server:0.0.1 .
```

`deployment.yaml` 与 `mysql.yaml` 都引用了这个镜像，`imagePullPolicy` 是 `IfNotPresent`。
单节点集群（kind / minikube / Docker Desktop）还需要把镜像送进节点：

```bash
# kind
kind load docker-image paimon-rest-server:0.0.1

# minikube
minikube image load paimon-rest-server:0.0.1
```

多节点集群请推到镜像仓库，并把清单里的 `image` 改成带仓库地址的完整引用。

### 2.3 应用

```bash
kubectl apply -k deploy/kubernetes

# 看进度
kubectl -n paimon-rest get pods -w
```

期望顺序：`paimon-mysql-0` 先 Init → Running → Ready，然后
`paimon-rest-server` 的 `wait-for-mysql` 初始化容器通过，主容器启动。

首次启动 MySQL 要执行建表语句（18 张表），会比后续重启慢。

### 2.4 验证

```bash
# 端口转发
kubectl -n paimon-rest port-forward svc/paimon-rest-server 8080:8080 &

# 目录发现：清单开了鉴权，无令牌应为 401
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/v1/config

# 带令牌应返回 warehouse 与 prefix
curl -s -H "Authorization: Bearer $REST_TOKEN" http://127.0.0.1:8080/v1/config

# 管理面：列出 catalog，应能看到预置的 paimon
curl -s -H "Authorization: Bearer $REST_TOKEN" \
  http://127.0.0.1:8080/api/management/v1/catalogs
```

---

## 3. 清单构成

| 文件 | 内容 | 作用 |
| --- | --- | --- |
| `namespace.yaml` | Namespace | 独立命名空间，删除时能一次清干净 |
| `secret.yaml` | Secret | MySQL 口令与 REST 令牌 |
| `configmap.yaml` | ConfigMap | 服务端配置的 K8s 覆盖层（`application-k8s.yml`） |
| `warehouse-pvc.yaml` | PVC | Paimon 表数据与元数据文件的落盘位置 |
| `mysql.yaml` | Service + StatefulSet | 元数据存储，含首次初始化逻辑 |
| `deployment.yaml` | Deployment | 服务端本体，含三类探针与安全上下文 |
| `service.yaml` | Service | 集群内入口，端口 8080 |
| `pdb.yaml` | PodDisruptionBudget | 约束节点排空时的中断 |
| `ingress.yaml` | Ingress | 集群外入口（可选） |
| `kustomization.yaml` | — | 组装、命名空间注入、统一标签 |

分层的做法：**结构性配置在 ConfigMap**（可读、可评审、可 diff），
**凭据在 Secret**（可单独授权，不进版本库）。ConfigMap 里以 `${DB_PASSWORD}`
这样的占位符引用 Secret 注入的环境变量。

---

## 4. 几个取舍

这些都是有意为之，改动前建议先读一下理由。

### 4.1 副本数固定为 1

warehouse 用的是 `file://` 加 `ReadWriteOnce` 卷。扩到 2 个副本后，第二个 Pod
要么调度不到有卷的节点，要么在不同节点上各挂一份不同的数据——后者的表现是
「建的表时有时无」，取决于请求落到哪个 Pod。

**要横向扩展，必须先把仓库换成对象存储**（S3 / Azure Blob / GCS）。
服务端已支持这几种存储类型，换成 `s3://...` 之后不再需要 `warehouse-pvc.yaml`，
多副本也就没有共享问题。同时 PDB 应从 `maxUnavailable: 1` 改成
`minAvailable: 1`，Service 不需要做会话保持。

### 4.2 探针用 exec + curl，而不是 httpGet

`httpGet` 只把 200–399 视为成功。清单开启了鉴权，而 `/v1/config` 是规格里唯一
豁免权限判定的端点，未带令牌时返回 401——用 `httpGet` 的话 readinessProbe
永远失败，Pod 永远不会被加入 Endpoints。表现是「容器在跑、日志正常、
但 Service 没有后端」。

所以探针显式把 401/403 也算作「进程已经在处理请求」，与 Dockerfile 里的
`HEALTHCHECK` 保持一致。

### 4.3 建表语句走镜像，不走 ConfigMap

直觉做法是给 DDL 加一段 `configMapGenerator`，挂到 MySQL 的
`/docker-entrypoint-initdb.d`。这行不通：kustomize 拒绝加载 kustomization
目录之外的文件（防止 `..` 逃逸），要引用仓库里的 `sql/schema-mysql.sql`
就得放弃 `kubectl apply -k`；而把 DDL 复制一份进 `deploy/` 又必然漂移。

所以 DDL 由 Dockerfile 打进镜像，MySQL 的 `seed-initdb` 初始化容器再把它
投递到初始化目录。附加的好处是 **DDL 与 jar 来自同一次构建**——
而 `ddl-auto: validate` 校验的正是 jar 里的实体定义，两者天然同版本。

代价：**改完 DDL 必须重新构建镜像**，只改清单不生效。

### 4.4 单副本下的 PDB 用 maxUnavailable

PDB 在单副本时无法两全：`minAvailable: 1` 会让节点排空永远卡住（报错只说
「被 PDB 阻止」），`maxUnavailable: 1` 等于没有保护但排空能完成。
这里选后者——与其留下一个会卡住集群运维的死结，不如显式承认单副本没有冗余。

### 4.5 MySQL 容器不以非 root 运行

MySQL 官方镜像的 entrypoint 需要以 root 启动，先 chown 数据目录、初始化数据，
再降权到 mysql 用户运行真正的 mysqld。强行指定非 root 会让它在初始化阶段就失败。
服务端容器则相反，全程以 uid 10001 运行，且根文件系统只读。

### 4.6 优雅停机的三处配合

| 位置 | 值 | 作用 |
| --- | --- | --- |
| `application-k8s.yml` | `spring.lifecycle.timeout-per-shutdown-phase: 30s` | 在途请求排空的等待上限 |
| Deployment | `terminationGracePeriodSeconds: 45` | 必须大于上一项，留 15s 给 preStop 与 JVM 收尾 |
| Deployment | `preStop: sleep 5` | 等 Service 的 Endpoints 先摘掉本 Pod——摘除是异步的，不等的话这段窗口里的新请求会打到已停止监听的端口 |

只设其中的一两项，都会让优雅停机在某条路径上失效。

---

## 5. 校验

清单的内部一致性有脚本检查，不需要集群：

```bash
python3 scripts/verify-k8s-manifests.py
```

它用 `kubectl kustomize` 渲染后逐项核对跨文件的引用：selector 能否选中 Pod、
卷名与卷定义是否对得上、`secretKeyRef` 的 key 是否存在、
**配置里的 `${...}` 占位符是否都有对应的环境变量**、探针端口与 containerPort
是否一致、清单引用的镜像是否都在 Dockerfile 里有构建命令。

这些错误在 `kubectl apply` 时都不会报，只在运行时表现成 Pod 一直 Pending
或 Spring 报「解析不了占位符」，报错位置离真正的原因很远。

---

## 6. 已验证到什么程度

本机的 Kubernetes 集群当前不可达，因此**没有做过真实的 `kubectl apply` 与
Pod 启动验证**。已经做过的是：

| 项目 | 方式 | 结果 |
| --- | --- | --- |
| kustomize 组装 | `kubectl kustomize deploy/kubernetes` | 渲染出 10 个资源，命名空间与标签注入正确 |
| 清单内部引用 | `scripts/verify-k8s-manifests.py` | §1–§9 全部通过 |
| 建表语句可用 | 在真实 MySQL 8.0.46 上执行 `sql/schema-mysql.sql` | 18 张表建出，`ddl-auto: validate` 通过 |
| **配置能否被服务端解析** | 把 ConfigMap 里的 `application-k8s.yml` 原样交给服务端（仅替换 MySQL 主机名与 warehouse 路径这两处本机不可达的值） | profile 生效、`${DB_PASSWORD}` 与 `${REST_TOKEN}` 解析成功、Hikari 连上 MySQL |
| **鉴权与授权** | 同上实例 | 无令牌 401、错误令牌 401、正确令牌 200（`/v1/config` 与管理 API 都是） |
| **warehouse 配置生效** | 同上实例 | `/v1/config` 返回配置的路径；建表后表路径为 `<warehouse>/k8s_check.db/orders` |
| **预置 catalog** | 同上实例 | `paimon` catalog 按 `initial-catalog` 建出，`storageType=FILE` |
| 端到端可用 | 同上实例 | 建库 200、建表 200、读回表列表正确、MySQL 中有对应记录 |

结论：**清单逻辑与配置解析已经验证，剩下的是集群侧的行为**（调度、卷挂载、
镜像拉取、探针在真实环境下的时序）。首次在集群里部署时，重点看这几处。

---

## 7. 常见问题

**Pod 一直 Pending**
多半是 PVC 没绑上——集群没有默认 StorageClass。

**服务端容器一直 ContainerCreating，事件里说找不到 configmap 或 secret**
清单没渲染就 apply 了。用 `kubectl apply -k deploy/kubernetes`，
别直接 `kubectl apply -f deploy/kubernetes/`（后者不会执行 kustomize 的
命名空间注入，资源会落到 `default`）。

**readinessProbe 一直失败**
先确认容器端口是不是 8080，以及服务端是否真的起来了
（`kubectl logs`）。探针容忍 401/403，所以鉴权本身不会导致它失败。

**ImagePullBackOff，但 `docker images` 里明明有这个镜像**
镜像是本地构建的，节点拉的却不是同一个。单节点集群用第 2.2 节的
`load` 命令；多节点集群推仓库。

**改了 `sql/schema-mysql.sql` 但新集群建出的表还是旧的**
DDL 只在镜像构建时进入镜像，改完要重新 `docker build`（见 4.3）。

**已有数据卷上怎么升级表结构**
`/docker-entrypoint-initdb.d` 只在数据目录为空时执行一次。升级流程是：
重新生成 DDL → 重新构建镜像 → 手工在库上执行迁移 →
滚动更新服务端（`ddl-auto: validate` 会校验结果，不一致就启动失败）。
