#!/bin/bash
# 一键跑通「Spark 通过 Paimon REST Catalog 建表」示例。
#
# 为什么脚本要自己起服务端：示例的意义在于「照着跑就能看到结果」。
# 如果读者得先准备 MySQL、配数据源、手工起进程，能跑起来的概率会低很多。
# 这里用 h2 内存库，数据即用即丢，跑完不留下需要清理的状态，也不要求本机有 MySQL。
#
# 不要求预先安装 Spark 发行版：classpath 由本仓库的 Maven 依赖提供
# （paimon-rest-spark 模块的 test 依赖里有 Paimon 的 Spark bundle）。
#
# 用法：
#   ./run-example.sh                  # 默认端口 18080，跑完自动停服务端
#   PORT=19090 ./run-example.sh       # 换端口
#   KEEP=1 ./run-example.sh           # 保留服务端，便于用 curl 接着看
#   TOKEN=my-token ./run-example.sh   # 换令牌（服务端与客户端同步用同一个值）
#
# 前置：
#   - 已执行过 ./mvnw -o install -DskipTests
#     （需要服务端 jar，以及 `spark` 模块在本地 m2 里解析出的依赖）
#
# 只想在自己的 Spark 集群上手工试，不需要这个脚本：
#   把 spark-defaults.conf 放进发行版的 conf/，再按
#   docs/spark-paimon-rest-e2e.md 第 2 节的 SQL 执行即可。
set -u

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DIR=$(cd "$(dirname "$0")" && pwd)
PORT=${PORT:-18080}
BASE=http://127.0.0.1:$PORT
TOKEN=${TOKEN:-root}
DATABASE=${DATABASE:-demo}
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}

JAR=$ROOT/paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar
MVNW=$ROOT/mvnw
BUILD=$DIR/target
SERVER_LOG=$(mktemp -t paimon-rest-example.XXXXXX.log)
SPID=""

cleanup() {
  if [ -n "$SPID" ]; then
    kill "$SPID" 2>/dev/null || true
    wait "$SPID" 2>/dev/null || true
  fi
}
if [ -z "${KEEP:-}" ]; then
  trap cleanup EXIT
fi

if [ ! -f "$JAR" ]; then
  echo "缺少服务端 jar：$JAR"
  echo "请先执行：./mvnw -o install -DskipTests"
  exit 1
fi

# ---------------------------------------------------------------------------
# 1. 依赖 classpath
# ---------------------------------------------------------------------------
mkdir -p "$BUILD"
CP_FILE=$BUILD/classpath.txt
if [ ! -s "$CP_FILE" ]; then
  echo "== 解析 Spark 侧依赖（写入 ${CP_FILE}）=="
  # includeScope=test：Paimon 的 Spark bundle 与 spark-hive 是 test 依赖
  # （本模块自己不依赖 Paimon 运行时，只在示例与测试里用）。
  # -o 走本地仓库；依赖缺失时去掉它，或先跑一次 ./mvnw install。
  "$MVNW" -o -q -pl paimon-rest-spark dependency:build-classpath \
    -Dmdep.includeScope=test -Dmdep.outputFile="$CP_FILE" || {
      echo "依赖解析失败。若本地 m2 仓库没有这些构件，先执行：./mvnw install -DskipTests"
      exit 1
    }
fi
CP=$(cat "$CP_FILE")

# ---------------------------------------------------------------------------
# 2. 编译示例
# ---------------------------------------------------------------------------
echo "== 编译示例 =="
# -proc:none 只是让输出干净：classpath 里带着若干个注解处理器（Spark 与 Paimon
# 的依赖树里混进来的），javac 默认启用它们并打印一段「已启用批注处理」的提示。
# 示例代码不需要注解处理。它与下面提到的 collectAsList 那件事无关——
# 那个问题的原因是 Scala 的泛型数组签名，见 SparkPaimonRestExample 里的注释。
"$JAVA_HOME/bin/javac" -proc:none -encoding UTF-8 -cp "$CP" -d "$BUILD/classes" \
  "$DIR/SparkPaimonRestExample.java"

# ---------------------------------------------------------------------------
# 3. 起服务端
# ---------------------------------------------------------------------------
echo "== 启动服务端（端口 ${PORT}，日志 ${SERVER_LOG}）=="
"$JAVA_HOME/bin/java" -jar "$JAR" \
  --spring.profiles.active=h2 \
  --server.port="$PORT" \
  --paimon.rest.auth.enabled=true \
  "--paimon.rest.auth.tokens[0]=$TOKEN" \
  --paimon.rest.authorization.enabled=true \
  "--paimon.rest.authorization.service-admins[0]=$TOKEN" \
  "--paimon.rest.authorization.bootstrap-principal=$TOKEN" \
  --paimon.rest.auto-create-catalog=true \
  >"$SERVER_LOG" 2>&1 &
SPID=$!

for _ in $(seq 1 60); do
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" "$BASE/api/management/v1/catalogs" 2>/dev/null || true)
  if [ "$code" = "200" ]; then
    break
  fi
  sleep 1
done
if [ "$code" != "200" ]; then
  echo "服务端未就绪，日志末尾："
  tail -30 "$SERVER_LOG"
  exit 1
fi
echo "   服务端就绪"

# ---------------------------------------------------------------------------
# 4. 跑示例
# ---------------------------------------------------------------------------
# --add-opens 是 Java 17+ 上跑 Spark 3.5 的必要条件：Spark 会反射访问
# java.base 里默认封装的那几个包，缺了会报 InaccessibleObjectException。
"$JAVA_HOME/bin/java" \
  -cp "$BUILD/classes:$CP" \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  "-Dpaimon.uri=$BASE" \
  "-Dpaimon.token=$TOKEN" \
  "-Dpaimon.database=$DATABASE" \
  com.example.paimonrest.examples.SparkPaimonRestExample
STATUS=$?

# ---------------------------------------------------------------------------
# 5. 收尾
# ---------------------------------------------------------------------------
echo
if [ -n "${KEEP:-}" ]; then
  echo "服务端保留在 ${BASE}（PID ${SPID}），日志 ${SERVER_LOG}"
  echo "停止：kill $SPID"
else
  echo "示例结束（退出码 ${STATUS}），服务端已停止。"
fi
exit $STATUS
