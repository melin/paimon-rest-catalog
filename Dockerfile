# Paimon Rest Catalog Server 镜像。
#
# 构建（在仓库根目录执行，构建上下文必须是根目录：需要同时看到父 POM 与子模块）：
#   docker build -t paimon-rest-server:0.0.1 .
#   docker run --rm -p 8080:8080 \
#     -e SPRING_DATASOURCE_URL='jdbc:mysql://host.docker.internal:3306/paimon_catalog?...' \
#     paimon-rest-server:0.0.1
#
# 只想快速试一下、不接 MySQL 时用内存库：
#   docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=h2 paimon-rest-server:0.0.1
#
# 为什么是多阶段：本仓库的构建需要 JDK、Maven 与约 300MB 的依赖，
# 而运行只需要一个 JRE 加一个 jar。分阶段后镜像里不含 Maven 仓库与源码。

# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# 构建阶段
# ---------------------------------------------------------------------------
# 用镜像自带的 mvn，不用 ./mvnw：wrapper 的 distributionType=only-script 需要联网
# 下载 Maven 发行包，而这一步在镜像里没有必要——基础镜像已经是 Maven 3.9.x，
# 与 .mvn/wrapper/maven-wrapper.properties 声明的 3.9.16 同一小版本线。
FROM maven:3.9-eclipse-temurin-21 AS builder

# 解析依赖用的仓库地址。默认值是 Maven Central 的原始域名，
# 与 Maven 内置的 repo.maven.apache.org 是同一份内容。
#
# 为什么不让 Maven 用内置地址：某些网络环境里 Maven Central 会对特定出口 IP
# 返回 403（提示 "Requests from open proxy and relay services are blocked"），
# 于是镜像构建在解析 spring-boot-starter-parent 时就失败，而本机 mvn 却正常——
# 排查起来很容易被误判成「版本号写错」。换成 repo1 域名可绕开这类节点级封锁。
# 走企业内网镜像时覆盖它即可：
#   docker build --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public .
ARG MAVEN_MIRROR_URL=https://repo1.maven.org/maven2

RUN mkdir -p /root/.m2 \
    && printf '%s\n' \
        '<?xml version="1.0" encoding="UTF-8"?>' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"' \
        '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' \
        '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>build-mirror</id>' \
        "      <url>${MAVEN_MIRROR_URL}</url>" \
        '      <mirrorOf>central</mirrorOf>' \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' > /root/.m2/settings.xml

WORKDIR /workspace

# 整个仓库一起复制。上下文大小由 .dockerignore 控制：target/ 与 .git/ 都不进上下文。
# 依赖下载挂在缓存挂载上，重复构建时不必重新下载（Requires BuildKit，默认已开启）。
COPY . .

# 只构建服务端模块。加 -am 是因为聚合 POM 下单独构建子模块需要父 POM 参与反应堆，
# 而 paimon-rest-spark 与本次镜像无关，不构建以缩短时间。
# 运行测试有意留给 CI：镜像构建只需产物本身。
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -pl paimon-rest-server -am clean package -DskipTests

# ---------------------------------------------------------------------------
# 运行阶段
# ---------------------------------------------------------------------------
# Boot 4 要求 JDK 17 起，这里选 21 LTS（本仓库的测试与端到端验收都在 21 上跑过）。
# 不选 alpine：musl 与 glibc 在少数依赖上行为不同，而本镜像并不缺那几 MB。
FROM eclipse-temurin:21-jre-noble

# curl 不是运行时依赖，只为两件事装：Docker 的 HEALTHCHECK，以及容器内排查
# （kubectl exec 进容器后能直接 curl 本机端点）。Kubernetes 的探针不依赖它。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行。uid/gid 固定成 10001，便于与 Kubernetes 的 runAsUser、
# emptyDir 的 fsGroup 对齐；随机 uid 会让挂载卷的属主判定变得不可预期。
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=builder --chown=app:app /workspace/paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar /app/app.jar
# 建表语句一并放进镜像：部署时 initContainer 要拿它初始化空库，
# 直接从镜像里取可以避免「镜像里的代码」与「集群里的 DDL」版本不一致。
#
# 为什么先建目录、再单独复制文件，而不是一句 COPY 带 --chmod 搞定：
#
# 1) 不能省掉权限设置。COPY 会原样保留源文件的权限位，而
#    scripts/gen-mysql-ddl.sh 用 mktemp 生成中间文件（0600）再拷到交付路径，
#    于是镜像里也会是 0600——只有属主 app(10001) 读得到，
#    而 K8s 的 initContainer 以别的 uid 运行时直接失败。
#
# 2) 但也不能直接把 --chmod 加在一条指向 /app/sql/schema-mysql.sql 的 COPY 上。
#    Docker 会为多级目标路径自动创建中间目录，而 --chmod 同样作用在这些目录上，
#    结果是 /app/sql 变成 drw-r--r--（0644）。目录没有 x 位就无法遍历，
#    里面的文件即便 0644 也读不到，而报错说的是
#    "cannot open /app/sql/schema-mysql.sql: Permission denied"——指向文件，
#    真正的原因在目录上，很容易往错的方向查。
#
# 所以目录与文件的权限分开设：目录 0755（可遍历），文件 0644（可读不可写）。
RUN mkdir -p /app/sql && chown app:app /app/sql && chmod 0755 /app/sql

COPY --chown=app:app --chmod=0644 sql/schema-mysql.sql /app/sql/schema-mysql.sql

USER 10001:10001

EXPOSE 8080

# 用 JAVA_TOOL_OPTIONS 而不是 ENTRYPOINT 里的 sh -c "$JAVA_OPTS"：
# 后者会多起一层 shell，信号（SIGTERM）需要靠 exec 才能正确传给 JVM，
# 而容器里 SIGTERM 不达会导致每次滚动更新都等到 grace period 结束才被杀。
# 这里用 JVM 自己认识的环境变量，ENTRYPOINT 保持 exec 形式，信号直达。
#
# -XX:MaxRAMPercentage 让堆随容器内存限额伸缩；配合 K8s 的 limits 使用。
# ExitOnOutOfMemoryError 让 OOM 后进程退出由编排层重启，而不是留着一个抖动的进程。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

# 探针打 GET /v1/config：它是规格里的目录发现端点，也是唯一在授权开启时
# 仍豁免权限判定的端点（见 CatalogAccessRules.isPublic 与 docs/authorization.md）。
# 鉴权开启时无令牌会得到 401，那同样说明进程已经能处理请求，因此把 401/403 也算作存活。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 \
    CMD curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/v1/config \
        | grep -qE '^(200|401|403)$' || exit 1

# exec 形式：java 是 PID 1，SIGTERM 直接到达 JVM，由 Spring Boot 走优雅停机。
# Kubernetes 传参时用 args:（会追加在这条命令之后），
# 例如 args: ["--spring.profiles.active=h2"]；JVM 参数则用 JAVA_TOOL_OPTIONS 覆盖。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
