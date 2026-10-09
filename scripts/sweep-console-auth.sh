#!/bin/bash
# 控制台登录的端到端验收：把浏览器登录走的每条路都打一遍。
#
# 为什么需要它：登录链路的故障在单元测试里看不出来——拦截器的排除列表写错、
# 响应字段名写成驼峰、令牌端点自己反被鉴权挡住、SPA 的深链回退漏了回调路径，
# 这些都不会让任何单元测试失败，只会让浏览器里的登录按钮没反应。
# 因此这里直接对**运行中的实例**发真实的 HTTP 请求。
#
# 前置：服务端以开启鉴权的方式启动，且有一个可用的主体凭据。
#   java -jar paimon-rest-server-*.jar --spring.profiles.active=h2 \
#     --paimon.rest.auth.enabled=true \
#     --paimon.rest.auth.access-token.signing-key="$(openssl rand -base64 48)" \
#     --paimon.rest.auth.tokens[0]=static-token-for-sweep
# 引导主体的 clientId / clientSecret 只在启动日志里出现一次（搜 "created bootstrap principal"）。
#
# 用法：
#   BASE=http://127.0.0.1:8080 \
#   CLIENT_ID=<引导主体 clientId> CLIENT_SECRET=<明文密钥> \
#   STATIC_TOKEN=static-token-for-sweep \
#   ./scripts/sweep-console-auth.sh
#
# 变量：
#   BASE          服务地址，默认 http://127.0.0.1:8080
#   CLIENT_ID     主体 clientId（必填）
#   CLIENT_SECRET 主体明文密钥（必填）
#   STATIC_TOKEN  静态令牌；给了就顺带验收那条降级路径
#   PRINCIPAL     期望的主体名，默认 root（引导主体名）
#   CONSOLE_USER  控制台账号，默认 admin（第 2 节用）
#   CONSOLE_PASSWORD 控制台密码，默认 admin
#   CONSOLE_PRINCIPAL 期望的主体名，默认与 CONSOLE_USER 相同（未配 principals 映射时）
#
# 连续运行有次数上限：第 2 节会故意输错一次密码，服务端对 console.password 的失败限速
# 默认是 5 次 / 分钟（按「来源 IP + 用户名」计数）。一分钟内跑超过 5 次会被 429 拒绝，
# 那不是回归，是限速生效。
#
# 每行格式为 <标记>[实际/期望] 说明，`ok ` 一致，`!! ` 不一致。退出码 1 表示有不一致。
set -u
B=${BASE:-http://127.0.0.1:8080}
AUTH_URL=$B/api/console/v1/auth
LOGIN_URL=$B/api/console/v1/login
TOKEN_URL=$B/api/catalog/v1/oauth/tokens
PROTECTED=$B/api/management/v1/catalogs
CALLBACK=$B/console/auth/callback
PRINCIPAL=${PRINCIPAL:-root}
CONSOLE_USER=${CONSOLE_USER:-admin}
CONSOLE_PASSWORD=${CONSOLE_PASSWORD:-admin}
CONSOLE_PRINCIPAL=${CONSOLE_PRINCIPAL:-$CONSOLE_USER}

if [ -z "${CLIENT_ID:-}" ] || [ -z "${CLIENT_SECRET:-}" ]; then
  echo "缺少 CLIENT_ID / CLIENT_SECRET；引导主体的凭据在服务端启动日志里（搜 'created bootstrap principal'）" >&2
  exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "需要 python3 解析 JSON 响应" >&2
  exit 2
fi

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT
BODY="$TMPD/body.json"
HEADER="$TMPD/header.txt"
OTHER="$TMPD/other.json"

pass=0
fail=0

note() { printf '\n========== %s ==========\n' "$1"; }

hit() { # hit <期望状态码> <说明> <curl 参数...>；响应体留在 $BODY，响应头留在 $HEADER
  local want="$1"; shift
  local label="$1"; shift
  local code
  code=$(curl -s --noproxy '*' -D "$HEADER" -o "$BODY" -w '%{http_code}' "$@" 2>/dev/null)
  local mark='ok '
  if [ "$code" = "$want" ]; then pass=$((pass + 1)); else mark='!! '; fail=$((fail + 1)); fi
  printf '%s[%s/%s] %s\n' "$mark" "$code" "$want" "$label"
}

jval() { # jval <点分路径>：取 JSON 字段，缺失得 <缺失>，布尔得 true/false
  python3 - "$BODY" "$1" <<'PY'
import json, sys
try:
    node = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print("<不是 JSON>"); raise SystemExit
try:
    for part in sys.argv[2].split("."):
        if not part:
            continue
        node = node[int(part)] if isinstance(node, list) else node.get(part)
except (KeyError, IndexError, TypeError, ValueError):
    node = None
if node is None:
    print("<缺失>")
elif isinstance(node, bool):
    print("true" if node else "false")
elif isinstance(node, list):
    print(",".join(str(x) for x in node))
else:
    print(node)
PY
}

jf() { # jf <期望值> <点分路径> <说明>
  local want="$1" label="$3" got
  got=$(jval "$2")
  local mark='ok '
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); else mark='!! '; fail=$((fail + 1)); fi
  # %b 让期望值里的 \n 能换行展示（多处文本用于「一字不差」的断言）
  printf '%s[%s] %-52s 期望=%b\n' "$mark" "$got" "$label" "$want"
}

jbool() { # jbool <点分路径> <说明>：只要求是布尔值。取值本身取决于服务端状态，不断死
  local label="$2" got
  got=$(jval "$1")
  local mark='ok '
  case "$got" in true | false) pass=$((pass + 1)) ;; *) mark='!! '; fail=$((fail + 1)) ;; esac
  printf '%s[%s] %s（应为布尔值）\n' "$mark" "$got" "$label"
}

jhas() { # jhas <点分路径> <子串> <说明>
  local needle="$2" label="$3" got
  got=$(jval "$1")
  local mark='ok '
  case "$got" in
    *"$needle"*) pass=$((pass + 1)) ;;
    *) mark='!! '; fail=$((fail + 1)) ;;
  esac
  printf '%s[%s] %s（应含 %s）\n' "$mark" "$got" "$label" "$needle"
}

header_has() { # header_has <子串> <说明>
  local mark='ok '
  if grep -qi "$1" "$HEADER"; then pass=$((pass + 1)); else mark='!! '; fail=$((fail + 1)); fi
  printf '%s[] %s（响应头应含 %s）\n' "$mark" "$2" "$1"
}

FORM="Content-Type: application/x-www-form-urlencoded"
JSON="Content-Type: application/json"

# ------------------------------------------------------------------ 1

note "1. 登录引导（必须在没有令牌时可用）"
hit 200 'GET /api/console/v1/auth（匿名）' "$AUTH_URL"
jf true authEnabled "服务端要求令牌"
jf true consoleRequired "控制台要求先登录"
jhas methods password "可用的登录方式里有用户名密码"
jf password methods.0 "用户名密码排在最前（不需要先建主体）"
jhas methods client-credentials "可用的登录方式里有客户端凭据"
jhas methods static-token "降级入口仍在列表里"
jf false session.authenticated "匿名时未认证"
jf /api/catalog/v1/oauth/tokens tokenEndpoint "令牌端点与服务端常量一致"

# ------------------------------------------------------------------ 2

note "2. 用户名密码（控制台默认方式）"
hit 400 '空请求体' -X POST -H "$JSON" "$LOGIN_URL"
jhas message username "错误信息说明缺了什么"

hit 400 '只给用户名' -X POST -H "$JSON" -d "{\"username\":\"$CONSOLE_USER\"}" "$LOGIN_URL"
jhas message username "同上"

hit 401 '密码不对' -X POST -H "$JSON" \
  -d "{\"username\":\"$CONSOLE_USER\",\"password\":\"definitely-not-the-password\"}" "$LOGIN_URL"
jhas message "invalid username or password" "报文措辞固定（不区分用户名与密码）"
cp "$BODY" "$OTHER"

hit 401 '用户名不存在' -X POST -H "$JSON" \
  -d '{"username":"no-such-user","password":"definitely-not-the-password"}' "$LOGIN_URL"
if diff -q "$BODY" "$OTHER" >/dev/null 2>&1; then
  pass=$((pass + 1)); printf 'ok [] 两种失败的响应体一字不差（否则可据此枚举用户名）\n'
else
  fail=$((fail + 1)); printf '!! [] 两种失败的响应体不一致：\n'; diff "$OTHER" "$BODY" | sed 's/^/       /'
fi

hit 200 '正确账号密码换令牌' -X POST -H "$JSON" \
  -d "{\"username\":\"$CONSOLE_USER\",\"password\":\"$CONSOLE_PASSWORD\"}" "$LOGIN_URL"
jf Bearer tokenType "tokenType（驼峰，不是 OAuth 的 token_type）"
jf "$CONSOLE_PRINCIPAL" principal "主体名（未配 principals 映射时就是用户名）"
PW_TOKEN=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("accessToken",""))' "$BODY")
if [ -n "$PW_TOKEN" ]; then
  pass=$((pass + 1)); printf 'ok [len=%s] accessToken 非空\n' "${#PW_TOKEN}"
else
  fail=$((fail + 1)); printf '!! [] accessToken 为空\n'
fi

hit 200 '带令牌访问受保护端点' -H "Authorization: Bearer $PW_TOKEN" "$PROTECTED"
hit 200 '带令牌回到登录引导' -H "Authorization: Bearer $PW_TOKEN" "$AUTH_URL"
jf true session.authenticated "服务端认可这个令牌"
jf console-access-token session.source "与客户端凭据签发的是同一种令牌"

# ------------------------------------------------------------------ 3

note "3. 令牌端点的请求形状（RFC 6749 第 5.2 节的错误码）"
hit 400 '缺 grant_type' -X POST -H "$FORM" -d "client_id=$CLIENT_ID" "$TOKEN_URL"
jf invalid_request error "错误码"

hit 400 '不支持的 grant_type' -X POST -H "$FORM" \
  -d grant_type=password -d "client_id=$CLIENT_ID" "$TOKEN_URL"
jf unsupported_grant_type error "错误码"

hit 400 '不支持的 scope' -X POST -H "$FORM" \
  -d grant_type=client_credentials -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
  -d 'scope=PRINCIPAL_ROLE:read_only' "$TOKEN_URL"
jf invalid_scope error "错误码（不是 invalid_request）"
jhas error_description PRINCIPAL_ROLE:ALL "错误描述点明可接受的取值"

hit 400 '请求体不是合法 JSON' -X POST -H "$JSON" -d '{not json' "$TOKEN_URL"
jf invalid_request error "错误码"
jf "<缺失>" message "没有退化成 catalog API 的 {message} 形状"

# ------------------------------------------------------------------ 3

note "4. 凭据校验（不泄露 clientId 是否存在）"
hit 401 '错误的 clientSecret' -X POST -H "$FORM" \
  -d grant_type=client_credentials -d "client_id=$CLIENT_ID" -d client_secret=not-the-secret "$TOKEN_URL"
jf invalid_client error "错误码"
header_has 'WWW-Authenticate' '401 带挑战头'
cp "$BODY" "$OTHER"

hit 401 '不存在的 clientId' -X POST -H "$FORM" \
  -d grant_type=client_credentials -d client_id=no-such-client -d "client_secret=$CLIENT_SECRET" "$TOKEN_URL"
if diff -q "$BODY" "$OTHER" >/dev/null 2>&1; then
  pass=$((pass + 1)); printf 'ok [] 两种失败的响应体一字不差\n'
else
  fail=$((fail + 1)); printf '!! [] 两种失败的响应体不一致：\n'; diff "$OTHER" "$BODY" | sed 's/^/       /'
fi

# ------------------------------------------------------------------ 4

note "5. 签发令牌并带着它访问受保护资源"
hit 200 '正确凭据换令牌' -X POST -H "$FORM" \
  -d grant_type=client_credentials -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" "$TOKEN_URL"
jf bearer token_type "token_type（RFC 6749 字段名）"
jf PRINCIPAL_ROLE:ALL scope "scope 被归一成唯一的可接受取值"
ACCESS_TOKEN=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("access_token",""))' "$BODY")
if [ -n "$ACCESS_TOKEN" ]; then
  pass=$((pass + 1)); printf 'ok [len=%s] access_token 非空\n' "${#ACCESS_TOKEN}"
else
  fail=$((fail + 1)); printf '!! [] access_token 为空\n'
fi

hit 401 '匿名访问受保护端点' "$PROTECTED"
hit 200 '带令牌访问受保护端点' -H "Authorization: Bearer $ACCESS_TOKEN" "$PROTECTED"

hit 200 '带令牌回到登录引导' -H "Authorization: Bearer $ACCESS_TOKEN" "$AUTH_URL"
jf true session.authenticated "服务端认可这个令牌"
jf "$PRINCIPAL" session.principal "主体名从凭据推导出来"
jf console-access-token session.source "令牌来源"
# 取值不断死：引导主体是按「待轮换」创建的（拿到明文密钥后应先轮换），
# 所以全新实例上是 true、轮换过就是 false。这里只保证这个字段确实传到了控制台
jbool session.credentialRotationRequired "凭据待轮换标记（控制台据此显示提醒）"

hit 401 '被改过的令牌' -H "Authorization: Bearer ${ACCESS_TOKEN}x" "$PROTECTED"
hit 401 '形状正确的伪造 JWT' -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJyb290In0.aaaa" "$PROTECTED"

# ------------------------------------------------------------------ 5

note "6. 静态令牌（降级入口）"
if [ -n "${STATIC_TOKEN:-}" ]; then
  hit 200 '静态令牌' -H "Authorization: Bearer $STATIC_TOKEN" "$PROTECTED"
  hit 200 '静态令牌回到登录引导' -H "Authorization: Bearer $STATIC_TOKEN" "$AUTH_URL"
  jf true session.authenticated "服务端认可静态令牌"
  jf static-token session.source "令牌来源"
  hit 401 '未登记的令牌' -H "Authorization: Bearer some-unregistered-token" "$PROTECTED"
else
  printf '(跳过：未提供 STATIC_TOKEN)\n'
fi

# ------------------------------------------------------------------ 6

note "7. 控制台静态资源与深链"
hit 200 'GET /console/ 入口页' "$B/console/"
if grep -q 'id="app"' "$BODY"; then
  pass=$((pass + 1)); printf 'ok [] 入口页是单页应用外壳\n'
else
  fail=$((fail + 1)); printf '!! [] 入口页不含 #app 挂载点\n'
fi
hit 200 'SSO 回调路径（深链须回退到入口页）' "$CALLBACK"
hit 200 '登录路径' "$B/console/login"
hit 404 '缺失的 assets 仍然 404（不能回退成入口页）' "$B/console/assets/missing.js"

# ------------------------------------------------------------------

printf '\n'
if [ "$fail" -gt 0 ]; then
  printf '验收未通过：%d / %d 项不一致\n' "$fail" "$((pass + fail))"
  exit 1
fi
printf '全部通过：%d 项\n' "$pass"
