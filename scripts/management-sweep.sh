#!/bin/bash
# Polaris Management API 全量验收：覆盖规格中全部 33 个 operation。
#
# 用法：
#   BASE=http://127.0.0.1:8080 ./scripts/management-sweep.sh
#
# 说明：
#   - 路径与请求体形状取自 spec/polaris-management-service.yml；
#   - 脚本会创建并清理 catalog mgmt 及其下的主体与角色，
#     因此要求目标实例上不存在名为 mgmt 的 catalog；
#   - 每行格式为 <标记>[实际状态码/期望状态码] 说明 响应片段。
set -u
B=${BASE:-http://127.0.0.1:8080}
M=$B/api/management/v1
V1=$B/v1/${PREFIX:-paimon}
J='Content-Type: application/json'
PY=/Users/melin/.workbuddy/binaries/python/versions/3.13.12/bin/python3

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
  printf '%s[%s/%s] %-62s %s\n' "$mark" "$code" "$want" "$label" "$(head -c 110 "$BODY")"
}
ok() { hit "$@"; }

# 从上一个响应体里取字段
field() { $PY -c "import json,sys;d=json.load(open(sys.argv[1]));print(d$1)" "$BODY" 2>/dev/null; }

# 从指定 URL 取字段，不受上一条响应影响
fetch() { curl -s --noproxy '*' "$1" | $PY -c "import json,sys;print(json.load(sys.stdin)$2)" 2>/dev/null; }

echo "========== 1. catalogs（5 个 operation） =========="
ok 200 "GET    /catalogs" "$M/catalogs"
ok 201 "POST   /catalogs (mgmt)" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"mgmt","properties":{"owner":"qa"},
             "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/mgmt-warehouse"]}}}' "$M/catalogs"
ok 409 "POST   /catalogs 重复 -> 409" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"mgmt","properties":{},
             "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/mgmt-warehouse"]}}}' "$M/catalogs"
ok 400 "POST   /catalogs storageType 未知 -> 400" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"badtype","properties":{},
             "storageConfigInfo":{"storageType":"HDFS","allowedLocations":[]}}}' "$M/catalogs"
ok 200 "GET    /catalogs/mgmt" "$M/catalogs/mgmt"
ok 404 "GET    /catalogs/nope -> 404" "$M/catalogs/nope"
VER=$(fetch "$M/catalogs/mgmt" "['entityVersion']")
echo "     mgmt entityVersion = $VER"
ok 200 "PUT    /catalogs/mgmt (版本匹配)" -X PUT -H "$J" \
  -d "{\"currentEntityVersion\":$VER,\"properties\":{\"tier\":\"gold\"}}" "$M/catalogs/mgmt"
ok 409 "PUT    /catalogs/mgmt 版本不符 -> 409" -X PUT -H "$J" \
  -d '{"currentEntityVersion":9999,"properties":{"tier":"silver"}}' "$M/catalogs/mgmt"
ok 200 "PUT    /catalogs/mgmt storageConfigInfo 更新仓库" -X PUT -H "$J" \
  -d "{\"currentEntityVersion\":$(($VER+1)),\"storageConfigInfo\":{\"storageType\":\"FILE\",\"allowedLocations\":[\"file:///tmp/mgmt-warehouse2\"]}}" \
  "$M/catalogs/mgmt"
echo "     管理面改过的仓库应同步到 catalog 面："
ok 200 "GET    /v1/mgmt 侧的 catalog 发现" "$B/v1/config?warehouse=mgmt"

echo "========== 1b. storageConfigInfo 四种存储类型 =========="
ok 201 "POST   /catalogs (S3 全字段)" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"s3-demo","properties":{"owner":"qa"},
   "storageConfigInfo":{"storageType":"S3","allowedLocations":["s3://analytics-bucket/warehouse/"],
     "roleArn":"arn:aws:iam::123456789001:role/polaris-s3","externalId":"external-id-1234",
     "userArn":"arn:aws:iam::123456789001:user/polaris","region":"ap-east-1",
     "endpoint":"https://s3.example.com:1234","stsEndpoint":"https://sts.example.com:1234",
     "endpointInternal":"https://s3.internal.example.com:1234","pathStyleAccess":true,
     "stsUnavailable":false,"kmsUnavailable":false,
     "encryptionKeys":["arn:aws:kms:ap-east-1:1:key/enc"],
     "decryptionKeys":["arn:aws:kms:ap-east-1:1:key/dec"]}}}' "$M/catalogs"
ok 200 "GET    /catalogs/s3-demo（S3 字段应原样回读）" "$M/catalogs/s3-demo"
S3_JSON=$(curl -s --noproxy '*' "$M/catalogs/s3-demo")
for f in roleArn externalId userArn region endpoint stsEndpoint endpointInternal pathStyleAccess; do
  case "$S3_JSON" in
    *"\"$f\""*) pass=$((pass + 1)); printf 'ok  %s\n' "[--/--] 回读含 $f" ;;
    *) fail=$((fail + 1)); printf '!!  %s\n' "[--/--] 回读缺 $f -> $S3_JSON" ;;
  esac
done
case "$S3_JSON" in
  *'"tenantId"'*|*'"gcsServiceAccount"'*)
    fail=$((fail + 1)); printf '!!  %s\n' "[--/--] S3 配置不应带出其它类型字段 -> $S3_JSON" ;;
  *) pass=$((pass + 1)); printf 'ok  %s\n' "[--/--] S3 配置不带其它类型字段" ;;
esac
ok 201 "POST   /catalogs (AZURE 全字段)" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"az-demo","properties":{},
   "storageConfigInfo":{"storageType":"AZURE",
     "allowedLocations":["abfss://container@account.blob.core.windows.net/warehouse/"],
     "tenantId":"72f988bf-86f1-41af-91ab-2d7cd011db47","multiTenantAppName":"polaris-multitenant",
     "consentUrl":"https://login.microsoftonline.com/tenant/adminconsent","hierarchical":true}}}' "$M/catalogs"
ok 400 "POST   /catalogs AZURE 缺 tenantId -> 400" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"az-bad","properties":{},
   "storageConfigInfo":{"storageType":"AZURE","allowedLocations":["abfss://c@a/"]}}}' "$M/catalogs"
ok 201 "POST   /catalogs (GCS 全字段)" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"gcs-demo","properties":{},
   "storageConfigInfo":{"storageType":"GCS","allowedLocations":["gs://demo-lake/warehouse/"],
     "gcsServiceAccount":"{\"type\":\"service_account\",\"project_id\":\"demo\"}"}}}' "$M/catalogs"
ok 400 "POST   /catalogs allowedLocations 含空串 -> 400" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"blank-loc","properties":{},
   "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/ok","   "]}}}' "$M/catalogs"
ok 400 "POST   /catalogs 缺 storageType -> 400" -X POST -H "$J" -d '{
  "catalog":{"type":"INTERNAL","name":"no-type","properties":{},
   "storageConfigInfo":{"allowedLocations":["file:///tmp/wh"]}}}' "$M/catalogs"
# 存储位置必须真的传导到数据面：新建的库要落在新位置之下
S3VER=$(fetch "$M/catalogs/s3-demo" "['entityVersion']")
ok 200 "PUT    /catalogs/s3-demo 换成 GCS" -X PUT -H "$J" \
  -d "{\"currentEntityVersion\":$S3VER,\"storageConfigInfo\":{\"storageType\":\"GCS\",\"allowedLocations\":[\"gs://moved-bucket/warehouse/\"],\"gcsServiceAccount\":\"{}\"}}" \
  "$M/catalogs/s3-demo"
ok 200 "POST   /v1/s3-demo/databases（换存储后建库）" -X POST -H "$J" \
  -d '{"name":"after_switch","options":{}}' "$B/v1/s3-demo/databases"
GW=$(curl -s --noproxy '*' "$B/v1/s3-demo/databases/after_switch")
case "$GW" in
  *'gs://moved-bucket/warehouse/after_switch.db'*)
    pass=$((pass + 1)); printf 'ok  %s\n' "[--/--] 库位置跟着存储一起换到 gs://moved-bucket/" ;;
  *) fail=$((fail + 1)); printf '!!  %s\n' "[--/--] 库位置未跟随新存储 -> $GW" ;;
esac
ok 204 "DELETE /catalogs/s3-demo" -X DELETE "$M/catalogs/s3-demo"
ok 204 "DELETE /catalogs/az-demo" -X DELETE "$M/catalogs/az-demo"
ok 204 "DELETE /catalogs/gcs-demo" -X DELETE "$M/catalogs/gcs-demo"

echo "========== 2. principals（8 个 operation） =========="
ok 201 "POST   /principals (alice)" -X POST -H "$J" \
  -d '{"principal":{"name":"alice","properties":{"dept":"data"}}}' "$M/principals"
SECRET=$(field "['credentials']['clientSecret']")
CLIENT=$(field "['credentials']['clientId']")
echo "     clientId=${CLIENT:0:8}… clientSecret=${SECRET:0:6}…（明文仅此一次）"
ok 409 "POST   /principals 重复 -> 409" -X POST -H "$J" \
  -d '{"principal":{"name":"alice"}}' "$M/principals"
ok 201 "POST   /principals (bob)" -X POST -H "$J" \
  -d '{"principal":{"name":"bob"}}' "$M/principals"
ok 200 "GET    /principals" "$M/principals"
ok 200 "GET    /principals/alice" "$M/principals/alice"
ok 404 "GET    /principals/nope -> 404" "$M/principals/nope"
AVER=$(fetch "$M/principals/alice" "['entityVersion']")
ok 200 "PUT    /principals/alice" -X PUT -H "$J" \
  -d "{\"currentEntityVersion\":$AVER,\"properties\":{\"dept\":\"science\"}}" "$M/principals/alice"
ok 409 "PUT    /principals/alice 版本不符 -> 409" -X PUT -H "$J" \
  -d '{"currentEntityVersion":9999,"properties":{}}' "$M/principals/alice"
ok 400 "PUT    /principals/alice 缺 currentEntityVersion -> 400" -X PUT -H "$J" \
  -d '{"properties":{}}' "$M/principals/alice"
ok 200 "POST   /principals/alice/rotate" -X POST "$M/principals/alice/rotate"
ok 200 "POST   /principals/alice/reset（服务端生成）" -X POST -H "$J" -d '{}' "$M/principals/alice/reset"
ok 200 "POST   /principals/bob/reset（指定 clientId/secret）" -X POST -H "$J" \
  -d '{"clientId":"bob-fixed-id","clientSecret":"bob-fixed-secret"}' "$M/principals/bob/reset"
ok 404 "POST   /principals/nope/reset -> 404" -X POST -H "$J" -d '{}' "$M/principals/nope/reset"

echo "========== 3. principal roles（9 个 operation） =========="
ok 201 "POST   /principal-roles (data_engineer)" -X POST -H "$J" \
  -d '{"principalRole":{"name":"data_engineer","properties":{"tier":"1"}}}' "$M/principal-roles"
ok 409 "POST   /principal-roles 重复 -> 409" -X POST -H "$J" \
  -d '{"principalRole":{"name":"data_engineer"}}' "$M/principal-roles"
ok 201 "POST   /principal-roles (service_admin)" -X POST -H "$J" \
  -d '{"principalRole":{"name":"service_admin"}}' "$M/principal-roles"
ok 200 "GET    /principal-roles" "$M/principal-roles"
ok 200 "GET    /principal-roles/data_engineer" "$M/principal-roles/data_engineer"
ok 404 "GET    /principal-roles/nope -> 404" "$M/principal-roles/nope"
RVER=$(fetch "$M/principal-roles/data_engineer" "['entityVersion']")
ok 200 "PUT    /principal-roles/data_engineer" -X PUT -H "$J" \
  -d "{\"currentEntityVersion\":$RVER,\"properties\":{\"tier\":\"2\"}}" "$M/principal-roles/data_engineer"
ok 200 "GET    /principal-roles/data_engineer/principals（尚无人持有）" "$M/principal-roles/data_engineer/principals"

echo "========== 4. 主体 ← principal role 授予 =========="
ok 201 "PUT    /principals/alice/principal-roles" -X PUT -H "$J" \
  -d '{"principalRole":{"name":"data_engineer"}}' "$M/principals/alice/principal-roles"
ok 201 "PUT    重复授予（幂等）" -X PUT -H "$J" \
  -d '{"principalRole":{"name":"data_engineer"}}' "$M/principals/alice/principal-roles"
ok 400 "PUT    缺 principalRole.name -> 400" -X PUT -H "$J" -d '{}' "$M/principals/alice/principal-roles"
ok 404 "PUT    未知角色 -> 404" -X PUT -H "$J" \
  -d '{"principalRole":{"name":"ghost"}}' "$M/principals/alice/principal-roles"
ok 200 "GET    /principals/alice/principal-roles" "$M/principals/alice/principal-roles"
ok 200 "GET    /principal-roles/data_engineer/principals（应含 alice）" "$M/principal-roles/data_engineer/principals"

echo "========== 5. catalog roles（catalog 内 11 个 operation 的上半） =========="
ok 201 "POST   /catalogs/mgmt/catalog-roles (catalog_reader)" -X POST -H "$J" \
  -d '{"catalogRole":{"name":"catalog_reader","properties":{"scope":"read"}}}' "$M/catalogs/mgmt/catalog-roles"
ok 409 "POST   重复 -> 409" -X POST -H "$J" \
  -d '{"catalogRole":{"name":"catalog_reader"}}' "$M/catalogs/mgmt/catalog-roles"
ok 201 "POST   /catalogs/mgmt/catalog-roles (catalog_writer)" -X POST -H "$J" \
  -d '{"catalogRole":{"name":"catalog_writer"}}' "$M/catalogs/mgmt/catalog-roles"
ok 200 "GET    /catalogs/mgmt/catalog-roles" "$M/catalogs/mgmt/catalog-roles"
ok 404 "GET    /catalogs/nope/catalog-roles -> 404" "$M/catalogs/nope/catalog-roles"
ok 200 "GET    /catalogs/mgmt/catalog-roles/catalog_reader" "$M/catalogs/mgmt/catalog-roles/catalog_reader"
ok 404 "GET    未知 catalog role -> 404" "$M/catalogs/mgmt/catalog-roles/ghost"
CRVER=$(fetch "$M/catalogs/mgmt/catalog-roles/catalog_reader" "['entityVersion']")
ok 200 "PUT    /catalogs/mgmt/catalog-roles/catalog_reader" -X PUT -H "$J" \
  -d "{\"currentEntityVersion\":$CRVER,\"properties\":{\"scope\":\"read-only\"}}" \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader"
ok 409 "PUT    版本不符 -> 409" -X PUT -H "$J" \
  -d '{"currentEntityVersion":9999,"properties":{}}' "$M/catalogs/mgmt/catalog-roles/catalog_reader"

echo "========== 6. 授权三形态（catalog / namespace / table） =========="
ok 201 "PUT    grants 新增 catalog 级授权" -X PUT -H "$J" \
  -d '{"grant":{"type":"catalog","privilege":"CATALOG_MANAGE_ACCESS"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 201 "PUT    grants 新增 namespace 级授权" -X PUT -H "$J" \
  -d '{"grant":{"type":"namespace","namespace":["silver"],"privilege":"NAMESPACE_LIST"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 201 "PUT    grants 新增 table 级授权" -X PUT -H "$J" \
  -d '{"grant":{"type":"table","namespace":["silver","sales"],"tableName":"orders","privilege":"TABLE_READ_PROPERTIES"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 201 "PUT    grants 重复新增（幂等）" -X PUT -H "$J" \
  -d '{"grant":{"type":"table","namespace":["silver","sales"],"tableName":"orders","privilege":"TABLE_READ_PROPERTIES"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 200 "GET    grants 列表" "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 400 "PUT    grant.type 未知 -> 400" -X PUT -H "$J" \
  -d '{"grant":{"type":"column","privilege":"TABLE_LIST"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 400 "PUT    权限层级不匹配 -> 400" -X PUT -H "$J" \
  -d '{"grant":{"type":"table","namespace":["a"],"tableName":"t","privilege":"POLICY_READ"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 400 "PUT    table 缺 tableName -> 400" -X PUT -H "$J" \
  -d '{"grant":{"type":"table","namespace":["a"],"privilege":"TABLE_LIST"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 201 "POST   grants 撤销 table 级授权" -X POST -H "$J" \
  -d '{"grant":{"type":"table","namespace":["silver","sales"],"tableName":"orders","privilege":"TABLE_READ_PROPERTIES"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"
ok 404 "POST   撤销不存在的授权 -> 404" -X POST -H "$J" \
  -d '{"grant":{"type":"view","namespace":["silver"],"viewName":"v","privilege":"VIEW_LIST"}}' \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/grants"

echo "========== 7. principal role ← catalog role 授予 =========="
ok 201 "PUT    /principal-roles/data_engineer/catalog-roles/mgmt" -X PUT -H "$J" \
  -d '{"catalogRole":{"name":"catalog_reader"}}' \
  "$M/principal-roles/data_engineer/catalog-roles/mgmt"
ok 201 "PUT    重复授予（幂等）" -X PUT -H "$J" \
  -d '{"catalogRole":{"name":"catalog_reader"}}' \
  "$M/principal-roles/data_engineer/catalog-roles/mgmt"
ok 404 "PUT    未知 catalog role -> 404" -X PUT -H "$J" \
  -d '{"catalogRole":{"name":"ghost"}}' \
  "$M/principal-roles/data_engineer/catalog-roles/mgmt"
ok 200 "GET    /principal-roles/data_engineer/catalog-roles/mgmt" \
  "$M/principal-roles/data_engineer/catalog-roles/mgmt"
ok 200 "GET    /catalogs/mgmt/catalog-roles/catalog_reader/principal-roles" \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader/principal-roles"
ok 204 "DELETE /principal-roles/data_engineer/catalog-roles/mgmt/catalog_reader" -X DELETE \
  "$M/principal-roles/data_engineer/catalog-roles/mgmt/catalog_reader"
ok 404 "DELETE 重复撤销 -> 404" -X DELETE \
  "$M/principal-roles/data_engineer/catalog-roles/mgmt/catalog_reader"

echo "========== 8. 清理（删除 204 语义） =========="
ok 204 "DELETE /principals/alice/principal-roles/data_engineer" -X DELETE \
  "$M/principals/alice/principal-roles/data_engineer"
ok 404 "DELETE 重复撤销 -> 404" -X DELETE \
  "$M/principals/alice/principal-roles/data_engineer"
ok 204 "DELETE /catalogs/mgmt/catalog-roles/catalog_writer" -X DELETE \
  "$M/catalogs/mgmt/catalog-roles/catalog_writer"
ok 204 "DELETE /catalogs/mgmt/catalog-roles/catalog_reader" -X DELETE \
  "$M/catalogs/mgmt/catalog-roles/catalog_reader"
ok 204 "DELETE /principal-roles/data_engineer" -X DELETE "$M/principal-roles/data_engineer"
ok 204 "DELETE /principal-roles/service_admin" -X DELETE "$M/principal-roles/service_admin"
ok 204 "DELETE /principals/alice" -X DELETE "$M/principals/alice"
ok 204 "DELETE /principals/bob" -X DELETE "$M/principals/bob"
ok 204 "DELETE /catalogs/mgmt（级联，规格 204）" -X DELETE "$M/catalogs/mgmt"
ok 404 "GET    /catalogs/mgmt（已删除）-> 404" "$M/catalogs/mgmt"

echo "========== 9. 两套 API 并存 =========="
ok 200 "GET    /v1/config（管理 API 不影响 catalog API）" "$B/v1/config"
ok 200 "GET    /rest-catalog-open-api.yaml" "$B/rest-catalog-open-api.yaml"
ok 200 "GET    /polaris-management-service.yml" "$B/polaris-management-service.yml"

echo
printf '通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
