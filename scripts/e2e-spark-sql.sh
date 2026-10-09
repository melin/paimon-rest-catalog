#!/bin/bash
# Spark SQL 扩展对着真实服务端的端到端验收。
#
# 为什么需要它：spark 模块的其它测试都用桩管理 API，只能证明「客户端与自己的假设
# 自洽」，证明不了「服务端真的接受这些请求」。本脚本起一个真实的 Paimon Rest Catalog Server，
# 用真实的 SparkSession 跑完整 SQL 链路，验证两侧实现的契约一致性。
#
# 用法：
#   ./scripts/e2e-spark-sql.sh              # 默认端口 18080，跑完自动停服务端
#   PORT=19090 ./scripts/e2e-spark-sql.sh   # 换端口
#   KEEP=1 ./scripts/e2e-spark-sql.sh       # 保留服务端不退出，便于手工继续调试
#
# 前置：
#   - 已执行过 ./mvnw -o install -DskipTests（需要 paimon-rest-server 的 jar）
#
# 说明：
#   - 服务端用内存 H2（显式指定 h2 profile，因为默认数据源已是 MySQL），
#     关闭即丢数据，因此脚本不依赖已有状态，也不要求本机有 MySQL；
#   - 令牌 root 即服务端配置里的服务管理员主体名（令牌未配置映射时退化为「令牌即主体名」）；
#   - 令牌 limited 经 token-principals 映射到主体 e2e_limited，用于验证授权确实被强制。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${PORT:-18080}
BASE=http://127.0.0.1:$PORT
JAR=$ROOT/paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar
LOG=$(mktemp -t paimon-rest-e2e.XXXXXX.log)
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}
MVNW=$ROOT/mvnw

if [ ! -f "$JAR" ]; then
  echo "缺少服务端 jar：$JAR"
  echo "请先执行：./mvnw -o install -DskipTests"
  exit 1
fi

echo "== 启动服务端（端口 ${PORT}，日志 ${LOG}） =="
"$JAVA_HOME/bin/java" -jar "$JAR" \
  --spring.profiles.active=h2 \
  --server.port="$PORT" \
  --paimon.rest.auto-create-catalog=true \
  --paimon.rest.auth.enabled=true \
  --paimon.rest.auth.tokens[0]=root \
  --paimon.rest.auth.tokens[1]=limited \
  --paimon.rest.auth.token-principals.limited=e2e_limited \
  --paimon.rest.authorization.enabled=true \
  --paimon.rest.authorization.service-admins[0]=root \
  --paimon.rest.authorization.bootstrap-principal=root \
  >"$LOG" 2>&1 &
SERVER_PID=$!

cleanup() {
  if [ -n "${KEEP:-}" ]; then
    echo "== KEEP 已设置：服务端仍在运行（pid ${SERVER_PID}），日志 ${LOG} =="
  else
    kill "$SERVER_PID" 2>/dev/null
    wait "$SERVER_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

# 等健康：管理 API 与 catalog API 都要能应答
echo "== 等待服务端就绪 =="
ready=0
for _ in $(seq 1 60); do
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
    -H 'Authorization: Bearer root' "$BASE/api/management/v1/catalogs" 2>/dev/null)
  if [ "$code" = "200" ]; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "服务端未在 60 秒内就绪（最后一次状态码 ${code:-无}）"
  echo "--- 日志尾部 ---"
  tail -40 "$LOG"
  exit 1
fi
echo "就绪。"

cd "$ROOT" || exit 1

echo "== 校验 docs/spark-sql-reference.md 与实现、规格一致 =="
# 静态检查，不需要服务端；放在这里是为了让一个命令覆盖 SQL 层的全部验收。
# 脚本要解析 OpenAPI 规格，因此需要一个装了 PyYAML 的解释器：
# 依次尝试 $PYTHON、python3，取第一个能 import yaml 的。
DOC_VERIFIER=$ROOT/scripts/verify-spark-sql-doc.py
for candidate in "${PYTHON:-}" python3; do
  [ -n "$candidate" ] || continue
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import yaml" >/dev/null 2>&1; then
    "$candidate" "$DOC_VERIFIER" || exit 1
    DOC_VERIFIED=1
    break
  fi
done
if [ -z "${DOC_VERIFIED:-}" ]; then
  echo "跳过文档校验：没有找到带 PyYAML 的解释器"
  echo "  安装：python3 -m pip install --user PyYAML"
  echo "  或指定：PYTHON=/path/to/python3 scripts/e2e-spark-sql.sh"
fi

echo "== 跑 Spark SQL 端到端验收 =="
# 这三个类必须同批运行：一个 JVM 只能有一个 SparkContext，而
# spark.sql.extensions 只在建会话时生效，因此它们共用 LiveSparkSession 的会话。
# 若把 ManagementSqlExecutionTests（用桩服务端、自建会话）也拉进来，
# 先建的那个会话会被复用，端点与扩展都会错位——LiveSparkSession 会就此报错。
#   ManagementSqlLiveServerTests  : 逐条语句对真实服务端验管理 API 契约
#   PaimonTableDdlTests           : 用 Spark SQL 通过 REST catalog 建 Paimon 表，
#                                   并核对落到服务端的 schema / 分区 / 表选项 / 路径
#   SparkSqlDocExamplesTests      : 执行参考文档第 11 节的示例，防止示例被改坏
"$MVNW" -o -pl paimon-rest-spark test \
  -Dtest=ManagementSqlLiveServerTests,PaimonTableDdlTests,SparkSqlDocExamplesTests \
  -DfailIfNoTests=false \
  -De2e.management.url="$BASE/api/management/v1" \
  -De2e.management.token=root
STATUS=$?

if [ "$STATUS" != "0" ]; then
  echo "== 验收失败；服务端日志尾部 =="
  tail -30 "$LOG"
fi
exit "$STATUS"
