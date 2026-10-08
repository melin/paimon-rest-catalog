#!/bin/bash
# Paimon REST Catalog 全量验收：覆盖规格中全部 60 个 operation。
#
# 用法：
#   BASE=http://127.0.0.1:8080 PREFIX=paimon ./scripts/api-sweep.sh
#
# 变量：
#   BASE    服务地址，默认 http://127.0.0.1:8080
#   PREFIX  catalog prefix，默认 paimon（须与 GET /v1/config 下发的 defaults.prefix 一致）
#
# 说明：
#   - 请求体形状取自 spec/rest-catalog-open-api.yaml，字段名与规格一致；
#   - 脚本假定目标实例为初始状态（database sales 尚不存在），
#     结尾会级联删除自己创建的资源，但中途失败会留下残留；
#   - 每行格式为 <标记>[实际状态码/期望状态码] 说明 响应片段，
#     标记 `ok ` 表示一致，`!! ` 表示不一致。
set -u
B=${BASE:-http://127.0.0.1:8080}
P=$B/v1/${PREFIX:-paimon}
J='Content-Type: application/json'

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT
BODY="$TMPD/body.txt"

pass=0
fail=0

hit() { # hit <期望状态码> <说明> <curl 参数...>
  local want="$1"; shift
  local label="$1"; shift
  local code
  code=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' "$@" 2>/dev/null)
  local mark="ok "
  if [ "$code" = "$want" ]; then pass=$((pass + 1)); else mark="!! "; fail=$((fail + 1)); fi
  printf '%s[%s/%s] %-58s %s\n' "$mark" "$code" "$want" "$label" "$(head -c 120 "$BODY")"
}
ok() { hit "$@"; }

echo "========== 1. config / databases =========="
ok 200 "GET  /v1/config" "$B/v1/config?warehouse=${PREFIX:-paimon}"
ok 200 "GET  /databases" "$P/databases"
ok 200 "POST /databases (sales)" -X POST -H "$J" -d '{"name":"sales","options":{"owner":"paimon"}}' "$P/databases"
ok 409 "POST /databases 重复 -> 409" -X POST -H "$J" -d '{"name":"sales"}' "$P/databases"
ok 200 "GET  /databases/sales" "$P/databases/sales"
ok 200 "POST /databases/sales (alter)" -X POST -H "$J" \
  -d '{"removals":["owner","absent"],"updates":{"tier":"gold"}}' "$P/databases/sales"

echo "========== 2. tables 基础 =========="
ok 200 "GET  /databases/sales/tables" "$P/databases/sales/tables"
ok 200 "POST /databases/sales/tables (orders)" -X POST -H "$J" -d '{
  "identifier":{"database":"sales","object":"orders"},
  "schema":{"fields":[{"id":0,"name":"id","type":"BIGINT NOT NULL"},
                      {"id":1,"name":"amount","type":"DECIMAL(10, 2)"},
                      {"id":2,"name":"dt","type":"VARCHAR(10)"}],
            "primaryKeys":["id"],"partitionKeys":["dt"],
            "options":{"bucket":"2"},"comment":"orders"}}' "$P/databases/sales/tables"
ok 200 "GET  /databases/sales/table-details?tableType=PAIMON" "$P/databases/sales/table-details?tableType=PAIMON"
ok 200 "GET  /tables" "$P/tables"
ok 200 "GET  /databases/sales/tables/orders" "$P/databases/sales/tables/orders"
ok 200 "POST /databases/sales/tables/orders (alter, 3 actions)" -X POST -H "$J" -d '{
  "changes":[{"action":"addColumn","fieldNames":["channel"],"dataType":"VARCHAR(20)","comment":"channel"},
             {"action":"setOption","key":"bucket","value":"4"},
             {"action":"renameColumn","fieldNames":["amount"],"newName":"total_amount"}]}' \
  "$P/databases/sales/tables/orders"

TID=$(curl -s --noproxy '*' "$P/databases/sales/tables/orders" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "     tableId = $TID"
ok 200 "GET  /tables/id/{tableId}" "$P/tables/id/$TID"

echo "========== 3. snapshots / commit =========="
ok 200 "POST /tables/orders/commit" -X POST -H "$J" -d '{
  "tableId":null,"baseSnapshotUuid":null,
  "snapshot":{"version":1,"uuid":"uuid-1","id":1,"schemaId":1,
              "baseManifestList":"base.manifest","deltaManifestList":"delta.manifest",
              "indexManifest":"index.manifest","commitUser":"tester","commitIdentifier":"c-1",
              "commitKind":"APPEND","timeMillis":1790739000000,
              "logOffsets":{"bucket-0":12},"totalRecordCount":100,"deltaRecordCount":100,
              "changelogRecordCount":0,"watermark":5},
  "statistics":[{"spec":{"dt":"2024-01-01"},"recordCount":100,"fileSizeInBytes":2048,
                 "fileCount":3,"lastFileCreationTime":1790739000000,"totalBuckets":2}]}' \
  "$P/databases/sales/tables/orders/commit"
ok 200 "GET  /tables/orders/snapshot" "$P/databases/sales/tables/orders/snapshot"
ok 200 "GET  /tables/orders/snapshots" "$P/databases/sales/tables/orders/snapshots"
ok 200 "GET  /tables/orders/snapshots/1" "$P/databases/sales/tables/orders/snapshots/1"

echo "========== 4. partitions =========="
ok 200 "POST /tables/orders/partitions" -X POST -H "$J" -d '{
  "partitionSpecs":[{"dt":"2024-01-01"},{"dt":"2024-02-01"}],
  "ignoreIfExists":true,
  "partitionStatistics":[{"spec":{"dt":"2024-02-01"},"recordCount":42,"fileSizeInBytes":512,"fileCount":2}],
  "replaceStatistics":true,
  "partitionOptions":[{"path":"custom/loc"},{}]}' "$P/databases/sales/tables/orders/partitions"
ok 200 "GET  /tables/orders/partitions" "$P/databases/sales/tables/orders/partitions"
ok 200 "POST /tables/orders/partitions/list-by-names" -X POST -H "$J" \
  -d '{"specs":[{"dt":"2024-01-01"}]}' "$P/databases/sales/tables/orders/partitions/list-by-names"
ok 200 "POST /tables/orders/partitions/mark" -X POST -H "$J" \
  -d '{"specs":[{"dt":"2024-01-01"}]}' "$P/databases/sales/tables/orders/partitions/mark"
ok 200 "POST /tables/orders/partitions/list-by-filter" -X POST -H "$J" \
  -d '{"filter":"{\"dt\":\"2024-01-01\"}","maxResults":10}' "$P/databases/sales/tables/orders/partitions/list-by-filter"
ok 200 "POST /tables/orders/partitions/drop" -X POST -H "$J" \
  -d '{"partitionSpecs":[{"dt":"2024-02-01"},{"dt":"2099-01-01"}]}' "$P/databases/sales/tables/orders/partitions/drop"

echo "========== 5. branches =========="
ok 200 "POST /tables/orders/tags (v1, 供 branch 派生)" -X POST -H "$J" \
  -d '{"tagName":"v1","timeRetained":"7d"}' "$P/databases/sales/tables/orders/tags"
ok 200 "POST /tables/orders/branches (dev <- tag v1)" -X POST -H "$J" \
  -d '{"branch":"dev","fromTag":"v1"}' "$P/databases/sales/tables/orders/branches"
ok 200 "GET  /tables/orders/branches" "$P/databases/sales/tables/orders/branches"
ok 200 "POST /branches/dev/rename -> dev2" -X POST -H "$J" \
  -d '{"toBranch":"dev2"}' "$P/databases/sales/tables/orders/branches/dev/rename"
ok 200 "POST /branches/dev2/forward" -X POST -H "$J" \
  -d '{"branch":"dev2"}' "$P/databases/sales/tables/orders/branches/dev2/forward"
ok 200 "DELETE /branches/dev2" -X DELETE "$P/databases/sales/tables/orders/branches/dev2"

echo "========== 6. tags / rollback =========="
ok 200 "GET  /tables/orders/tags" "$P/databases/sales/tables/orders/tags"
ok 200 "GET  /tables/orders/tags/v1" "$P/databases/sales/tables/orders/tags/v1"
ok 200 "POST /tables/orders/rollback (to tag v1)" -X POST -H "$J" \
  -d '{"instant":{"type":"tag","tagName":"v1"}}' "$P/databases/sales/tables/orders/rollback"
ok 200 "POST /tables/orders/rollback-schema (-> 0)" -X POST -H "$J" \
  -d '{"schemaId":0}' "$P/databases/sales/tables/orders/rollback-schema"
ok 200 "DELETE /tables/orders/tags/v1" -X DELETE "$P/databases/sales/tables/orders/tags/v1"

echo "========== 7. consumers / credential =========="
ok 200 "POST /tables/orders/consumers/reset" -X POST -H "$J" \
  -d '{"consumerId":"flink-job-1","nextSnapshotId":1}' "$P/databases/sales/tables/orders/consumers/reset"
ok 200 "GET  /tables/orders/consumers" "$P/databases/sales/tables/orders/consumers"
ok 200 "GET  /tables/orders/token" "$P/databases/sales/tables/orders/token"
ok 200 "POST /tables/orders/auth" -X POST -H "$J" \
  -d '{"select":["id","dt"]}' "$P/databases/sales/tables/orders/auth"
ok 403 "POST /tables/orders/auth 未知列 -> 403" -X POST -H "$J" \
  -d '{"select":["nope"]}' "$P/databases/sales/tables/orders/auth"

echo "========== 8. register / rename / drop =========="
ok 200 "POST /databases/sales/register" -X POST -H "$J" \
  -d '{"identifier":{"database":"sales","object":"ext_table"},"path":"s3://bucket/ext/table"}' \
  "$P/databases/sales/register"
ok 200 "GET  /databases/sales/tables/ext_table (isExternal)" "$P/databases/sales/tables/ext_table"
ok 200 "POST /tables/rename (orders -> orders_v2)" -X POST -H "$J" -d '{
  "source":{"database":"sales","object":"orders"},
  "destination":{"database":"sales","object":"orders_v2"}}' "$P/tables/rename"
ok 200 "GET  /databases/sales/tables/orders_v2" "$P/databases/sales/tables/orders_v2"
ok 200 "DELETE /databases/sales/tables/ext_table" -X DELETE "$P/databases/sales/tables/ext_table"

echo "========== 9. views (8 端点) =========="
ok 200 "POST /databases/sales/views (orders_view)" -X POST -H "$J" -d '{
  "identifier":{"database":"sales","object":"orders_view"},
  "schema":{"fields":[{"id":0,"name":"id","type":"BIGINT"}],
            "query":"SELECT id FROM orders","comment":"view"}}' "$P/databases/sales/views"
ok 200 "POST /databases/sales/views/orders_view (alter)" -X POST -H "$J" -d '{
  "changes":[{"action":"addDialect","dialect":"spark","query":"SELECT id FROM spark.orders"},
             {"action":"updateComment","comment":"order view"}]}' "$P/databases/sales/views/orders_view"
ok 200 "GET  /databases/sales/views/orders_view" "$P/databases/sales/views/orders_view"
ok 200 "GET  /databases/sales/views" "$P/databases/sales/views"
ok 200 "GET  /databases/sales/view-details" "$P/databases/sales/view-details"
ok 200 "GET  /views (全局)" "$P/views"
ok 200 "POST /views/rename" -X POST -H "$J" -d '{
  "source":{"database":"sales","object":"orders_view"},
  "destination":{"database":"sales","object":"orders_view_v2"}}' "$P/views/rename"
ok 200 "DELETE /databases/sales/views/orders_view_v2" -X DELETE "$P/databases/sales/views/orders_view_v2"

echo "========== 10. functions (7 端点) =========="
ok 200 "POST /databases/sales/functions (add_one)" -X POST -H "$J" -d '{
  "name":"add_one","inputParams":[{"id":0,"name":"a","type":"INT"}],
  "returnParams":[{"id":0,"name":"r","type":"INT"}],"deterministic":true,
  "definitions":{"default":{"type":"sql","definition":"a + 1"}},
  "comment":"increment","options":{"owner":"paimon"}}' "$P/databases/sales/functions"
ok 200 "POST /databases/sales/functions/add_one (alter)" -X POST -H "$J" -d '{
  "changes":[{"action":"addDefinition","name":"spark","definition":{"type":"lambda","language":"java"}},
             {"action":"updateComment","comment":"加一"}]}' "$P/databases/sales/functions/add_one"
ok 200 "GET  /databases/sales/functions/add_one" "$P/databases/sales/functions/add_one"
ok 200 "GET  /databases/sales/functions" "$P/databases/sales/functions"
ok 200 "GET  /databases/sales/function-details" "$P/databases/sales/function-details"
ok 200 "GET  /functions (全局)" "$P/functions"
ok 400 "POST /functions/add_one dropDefinition 缺失定义 -> 400" -X POST -H "$J" \
  -d '{"changes":[{"action":"dropDefinition","name":"ghost"}]}' "$P/databases/sales/functions/add_one"
ok 200 "DELETE /databases/sales/functions/add_one" -X DELETE "$P/databases/sales/functions/add_one"

echo "========== 11. semantic views (4 端点) =========="
ok 200 "POST /databases/sales/semantic-views/sales_model (upsert)" -X POST -H "$J" \
  -d '{"definition":{"format":"databricks-yaml","content":"version: 1\nmodel: sales"}}' \
  "$P/databases/sales/semantic-views/sales_model"
ok 200 "GET  /databases/sales/semantic-views/sales_model" "$P/databases/sales/semantic-views/sales_model"
ok 200 "GET  /databases/sales/semantic-views" "$P/databases/sales/semantic-views"
{ printf '{"definition":{"format":"databricks-yaml","content":"'
  head -c 1100000 /dev/zero | tr '\0' 'x'
  printf '"}}'
} > "$TMPD/big.json"
ok 413 "POST semantic-view 超 1 MiB -> 413" -X POST -H "$J" \
  --data-binary @"$TMPD/big.json" "$P/databases/sales/semantic-views/big_model"
ok 200 "DELETE /databases/sales/semantic-views/sales_model" -X DELETE "$P/databases/sales/semantic-views/sales_model"

echo "========== 12. 错误语义与清理 =========="
ok 404 "GET  /databases/nope (resourceType=DATABASE)" "$P/databases/nope"
ok 404 "GET  /databases/sales/tables/nope (TABLE)" "$P/databases/sales/tables/nope"
ok 400 "POST /databases/sales/tables/orders_v2 未知 action" -X POST -H "$J" \
  -d '{"changes":[{"action":"explode","fieldNames":["id"]}]}' "$P/databases/sales/tables/orders_v2"
ok 400 "POST /databases malformed json -> 400" -X POST -H "$J" -d '{not json' "$P/databases"
ok 200 "GET  /v1/nosuchprefix/databases（auto-create 默认开启，按设计自动登记）" "$B/v1/nosuchprefix/databases"
ok 404 "GET  /v1/paimon/nonsense (未知路径)" "$P/nonsense"
ok 405 "POST /v1/config (方法不允许)" -X POST "$B/v1/config"
ok 200 "GET  /rest-catalog-open-api.yaml (静态规格)" "$B/rest-catalog-open-api.yaml"
ok 200 "DELETE /databases/sales/tables/orders_v2" -X DELETE "$P/databases/sales/tables/orders_v2"
ok 200 "DELETE /databases/sales (级联)" -X DELETE "$P/databases/sales"

echo
printf '通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
