#!/bin/bash
# 生成 MySQL 建表语句并安装到 sql/schema-mysql.sql。
#
# 为什么要有这个脚本：DDL 必须与实体一致，手工维护 18 张表迟早会漂移。
# 建表语句由 Hibernate 依据实体元数据生成（见 MysqlDdlGeneratorTests），
# 本脚本只负责补文件头、落到交付路径，并把「是否发生变化」告诉调用者。
#
# 用法：
#   ./scripts/gen-mysql-ddl.sh              # 生成并覆盖 sql/schema-mysql.sql
#   OUT=/tmp/x.sql ./scripts/gen-mysql-ddl.sh
#
# 前置：JDK 21（与其它构建脚本一致）。
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${OUT:-$ROOT/sql/schema-mysql.sql}
# 建库语句里的库名，需要与 spring.datasource.url 中的库名一致
DB_NAME=${DB_NAME:-paimon_catalog}
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}

GENERATED=$(mktemp -t paimon-mysql-schema.XXXXXX.sql)
TARGET=$(mktemp -t paimon-mysql-schema-final.XXXXXX.sql)
trap 'rm -f "$GENERATED" "$TARGET"' EXIT

echo "== 由实体元数据生成 MySQL DDL =="
cd "$ROOT" || exit 1
"$ROOT/mvnw" -o -pl paimon-rest-server test \
  -Dtest=MysqlDdlGeneratorTests \
  -DfailIfNoTests=false \
  "-Dmysql.ddl.output=$GENERATED" \
  -q

if [ ! -s "$GENERATED" ]; then
  echo "未生成 DDL：$GENERATED"
  exit 1
fi

# 文件头固定，便于读者知道这是生成物以及如何重新生成
cat >"$TARGET" <<'HEADER'
-- Paimon Rest Catalog Server 元数据表结构（MySQL 8.0）
--
-- 本文件由 scripts/gen-mysql-ddl.sh 从 JPA 实体元数据生成，请勿手工修改：
-- 手工改动会在下次生成时丢失，需要改结构请改实体后重新生成。
--
-- 应用方式（库不存在时会一并创建，库名可用 DB_NAME=... 重新生成来改）：
--   mysql -h 127.0.0.1 -u root -p < sql/schema-mysql.sql
--
-- 几点说明：
--   - 库、表都必须使用 utf8mb4，否则中文标识符与 JSON 文本会乱码；
--   - 本文件用于初始化空库，重复执行会因表已存在而失败；要重建请先 drop database；
--   - 应用启动时的 ddl-auto 是 validate：本文件与实体不一致时会直接启动失败，
--     而不是悄悄改表，因此升级代码后若实体有变，需要重新生成并迁移；
--   - 唯一约束里的 namespace_hash / spec_hash 是定长 SHA-256 摘要：
--     MySQL 的索引键上限是 3072 字节（utf8mb4 下 768 字符），
--     而命名空间路径与分区 spec 的长度没有上界，直接用原文建唯一索引会被
--     ERROR 1071 拒绝。原文字段仍然保留，供等值查询使用。
--
HEADER

cat >>"$TARGET" <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE \`${DB_NAME}\`;

SQL

# Hibernate 的脚本输出以空行开头，去掉它，让文件头与首个建表语句之间不留空档
sed '/./,$!d' "$GENERATED" >>"$TARGET"

mkdir -p "$(dirname "$OUT")"
if [ -f "$OUT" ] && cmp -s "$OUT" "$TARGET"; then
  echo "无变化：$OUT"
else
  cp "$TARGET" "$OUT"
  # mktemp 建的是 0600，首次落盘（$OUT 不存在时）会把这份权限带过去，
  # 结果是交付文件在仓库里不可读。显式改回常规权限。
  chmod 644 "$OUT"
  echo "已写入：$OUT"
fi
# Hibernate 格式化后的语句带缩进，因此匹配前导空白
echo "建表语句 $(grep -cE '^[[:space:]]*create table' "$OUT") 条，唯一约束 $(grep -cE 'add constraint' "$OUT") 条，索引 $(grep -cE '^[[:space:]]*create index' "$OUT") 条"
