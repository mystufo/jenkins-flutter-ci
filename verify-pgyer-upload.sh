#!/usr/bin/env bash
#
# 蒲公英上传链路验证脚本，用于在构建机上独立复现 / 验证上传问题，无需跑一次完整 Jenkins 构建。
#
# 与 vars/pgyerUpload.groovy 走同一套「快速上传」三步式流程：
#   1. POST /apiv2/app/getCOSToken 取预上传凭证；
#   2. 把安装包直传返回的腾讯云 COS endpoint（成功为 204 No Content）；
#   3. 轮询 GET /apiv2/app/buildInfo 确认发布完成。
#
# 除 file 外的表单字段一律用 curl --form-string 提交，不能用 -F：-F 会把值里第一个分号之后的内容
# 当成 ;type= 之类的字段参数，而 COS 签名串形如 q-sign-time=1785000000;1785007200，用 -F 会被截断，
# COS 随后回 403 AccessDenied / Request has expired，看着像凭证过期，实际是字段被 curl 截断。
#
# 用法：
#   export PGYER_API_KEY=xxxx
#   ./verify-pgyer-upload.sh --token-only              # 零副作用：只验证鉴权与网关连通，不上传文件
#   ./verify-pgyer-upload.sh app.apk                   # 完整三步式（会在蒲公英产生一个新版本）
#   ./verify-pgyer-upload.sh --legacy app.apk          # 对照组：走旧版直传接口，用于复现 522
#   ./verify-pgyer-upload.sh --via-proxy app.apk       # 对照组：不绕过 HTTP(S)_PROXY，用于验证本地代理的影响
#
# 接口域名默认 api.pgyer.com，可用 PGYER_API_DOMAIN 换成官方备用域名（api.xcxwo.com / api.pgyeraapp.com）：
#   PGYER_API_DOMAIN=api.xcxwo.com ./verify-pgyer-upload.sh --token-only
#
# 退出码：0 全部通过；非 0 表示某一步失败，失败原因见输出。
#
set -uo pipefail

MODE_TOKEN_ONLY=false
USE_LEGACY=false
VIA_PROXY=false
APK_FILE=""
API_DOMAIN="${PGYER_API_DOMAIN:-api.pgyer.com}"

while [ $# -gt 0 ]; do
    case "$1" in
        --token-only)   MODE_TOKEN_ONLY=true ;;
        --legacy)       USE_LEGACY=true ;;
        --via-proxy)    VIA_PROXY=true ;;
        -h|--help)
            # 打印文件头部的连续注释块（跳过 shebang，遇到第一行非注释即停）。
            awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
            exit 0
            ;;
        -*)
            echo "未知参数: $1（可用 --token-only / --legacy / --via-proxy）" >&2
            exit 2
            ;;
        *) APK_FILE="$1" ;;
    esac
    shift
done

if [ -z "${PGYER_API_KEY:-}" ]; then
    echo "错误：请先设置环境变量 PGYER_API_KEY（值同 Jenkins 凭据 pgyer-api-key）" >&2
    exit 2
fi

if [ "${MODE_TOKEN_ONLY}" = false ] && [ -z "${APK_FILE}" ]; then
    echo "错误：需要传入安装包路径，或改用 --token-only 只做连通性验证" >&2
    exit 2
fi

if [ -n "${APK_FILE}" ] && [ ! -f "${APK_FILE}" ]; then
    echo "错误：文件不存在: ${APK_FILE}" >&2
    exit 2
fi

# 统一的 curl 调用入口。默认绕过代理：蒲公英与腾讯云 COS 均为境内服务，
# 经本地代理转发大文件会显著拉长上游耗时；加 --via-proxy 则保留系统代理设置做对照。
#
# 这里必须把 --noproxy '*' 写在函数体里，不能放进变量再展开：变量若不加引号展开，
# * 会被 shell 当通配符替换成当前目录的文件名，curl 就会把那些文件名当 URL
# （表现为一连串 "Could not resolve host: xxx.apk"）；若加引号展开则整串被当成单个参数。
run_curl() {
    if [ "${VIA_PROXY}" = true ]; then
        curl "$@"
    else
        curl --noproxy '*' "$@"
    fi
}

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

# 预创建响应文件：curl 连接失败时不会创建 -o 指定的文件，后续读取会报「No such file」。
: > "${WORK_DIR}/token.json"
: > "${WORK_DIR}/upload.log"
: > "${WORK_DIR}/info.json"
: > "${WORK_DIR}/legacy.json"

# 从 JSON 文件中按 key 逐层取值，取不到则输出空串。
# 优先用 python3；构建机若无 python3 会在首次调用时报错退出，此时请改用 jq 版本或手动看响应文件。
json_get() {
    local file="$1"
    shift
    python3 - "${file}" "$@" <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as fp:
        data = json.load(fp)
except Exception:
    print("")
    sys.exit(0)
for key in sys.argv[2:]:
    if not isinstance(data, dict) or key not in data:
        print("")
        sys.exit(0)
    data = data[key]
print("" if data is None else data)
PY
}

echo "==================== 环境信息 ===================="
echo "主机            : $(hostname)"
echo "curl            : $(curl --version 2>/dev/null | head -1)"
echo "接口域名        : ${API_DOMAIN}"
echo "HTTP_PROXY      : ${HTTP_PROXY:-(未设置)}"
echo "HTTPS_PROXY     : ${HTTPS_PROXY:-(未设置)}"
echo "NO_PROXY        : ${NO_PROXY:-(未设置)}"
echo "本次是否绕过代理: $([ "${VIA_PROXY}" = true ] && echo '否（保留系统代理，对照用）' || echo '是（--noproxy *）')"
if [ -n "${APK_FILE}" ]; then
    echo "安装包          : ${APK_FILE}（$(du -h "${APK_FILE}" | cut -f1)）"
fi
echo ""

# ---------------------------------------------------------------------------
# 对照组：旧版直传接口。整个包要推过蒲公英站点网关，用于复现 522。
# 这里固定用站点域名 www.pgyer.com 而非 API_DOMAIN，才能忠实复现改造前的失败现象。
# ---------------------------------------------------------------------------
if [ "${USE_LEGACY}" = true ]; then
    echo "==================== 对照组：旧版直传接口 ===================="
    echo "POST https://www.pgyer.com/apiv2/app/upload"
    LEGACY_STAT=$(run_curl -sS \
        --connect-timeout 15 --max-time 1800 \
        -o "${WORK_DIR}/legacy.json" \
        -w '%{http_code} %{time_total} %{size_upload} %{speed_upload}' \
        -F "file=@${APK_FILE}" \
        -F "_api_key=${PGYER_API_KEY}" \
        -F "buildUpdateDescription=verify-pgyer-upload.sh 链路验证" \
        https://www.pgyer.com/apiv2/app/upload || true)
    echo "统计（http_code 耗时s 字节数 速度B/s）: ${LEGACY_STAT}"
    echo "响应片段: $(head -c 300 "${WORK_DIR}/legacy.json")"
    LEGACY_CODE=$(echo "${LEGACY_STAT}" | awk '{print $1}')
    echo ""
    if [ "${LEGACY_CODE}" = "200" ]; then
        echo "结论：旧接口这次是通的（说明 522 为偶发，与包体积、瞬时带宽相关）"
        exit 0
    fi
    echo "结论：旧接口返回 ${LEGACY_CODE}。522 表示 Cloudflare 到源站超时，请求未进入蒲公英业务层。"
    echo "      这正是弃用该接口、改走 getCOSToken + COS 直传的原因。"
    exit 1
fi

# ---------------------------------------------------------------------------
# 第 1 步：取预上传凭证。请求体只有几 KB，能独立验证鉴权与网关连通。
# ---------------------------------------------------------------------------
echo "==================== 第 1 步：getCOSToken ===================="
TOKEN_STAT=$(run_curl -sS \
    --connect-timeout 15 --max-time 60 \
    -o "${WORK_DIR}/token.json" \
    -w '%{http_code} %{time_total}' \
    --form-string "_api_key=${PGYER_API_KEY}" \
    --form-string "buildType=android" \
    --form-string "buildUpdateDescription=verify-pgyer-upload.sh 链路验证" \
    "https://${API_DOMAIN}/apiv2/app/getCOSToken" || true)
TOKEN_HTTP_CODE=$(echo "${TOKEN_STAT}" | awk '{print $1}')
echo "http_code=${TOKEN_HTTP_CODE} 耗时=$(echo "${TOKEN_STAT}" | awk '{print $2}')s"

if [ "${TOKEN_HTTP_CODE}" != "200" ]; then
    echo "响应片段: $(head -c 300 "${WORK_DIR}/token.json")"
    echo ""
    echo "结论：网关层就没通（http_code=${TOKEN_HTTP_CODE}）。"
    echo "      5xx 多为网关 / 上游问题；000 为连接失败，检查出网链路与代理设置。"
    exit 1
fi

TOKEN_CODE=$(json_get "${WORK_DIR}/token.json" code)
if [ "${TOKEN_CODE}" != "0" ]; then
    echo "接口返回: code=${TOKEN_CODE} message=$(json_get "${WORK_DIR}/token.json" message)"
    echo ""
    echo "结论：网关通了但接口拒绝，通常是 API Key 无效或账号权限 / 额度问题。"
    exit 1
fi

COS_ENDPOINT=$(json_get "${WORK_DIR}/token.json" data endpoint)
BUILD_KEY=$(json_get "${WORK_DIR}/token.json" data key)

echo "endpoint = ${COS_ENDPOINT}"
echo "buildKey = ${BUILD_KEY}"

# 打印每个 params 字段的长度，并标出值里是否含分号：签名串含分号，一旦被 curl -F 截断，
# 这里的长度会明显短于原值，是区分「凭证真的过期」与「字段被截断」最直接的依据。
python3 - "${WORK_DIR}/token.json" <<'PY'
import json, sys

try:
    with open(sys.argv[1]) as fp:
        params = json.load(fp)["data"]["params"]
except Exception as exc:
    print("params 解析失败:", exc)
    sys.exit(0)

print("params 字段（字段名 / 值长度 / 是否含分号）:")
for key in sorted(params):
    value = params[key] or ""
    flag = "含分号，必须用 --form-string 提交" if ";" in value else "无分号"
    print("  %-24s %5d  %s" % (key, len(value), flag))
PY
echo "结论：鉴权与网关连通正常。"
echo ""

if [ "${MODE_TOKEN_ONLY}" = true ]; then
    echo "（--token-only 模式，未上传文件，蒲公英上不会产生新版本）"
    exit 0
fi

# ---------------------------------------------------------------------------
# 第 2 步：直传腾讯云 COS。COS 的 POST Object 要求 file 字段排在所有表单字段最后。
# ---------------------------------------------------------------------------
echo "==================== 第 2 步：直传 COS ===================="
echo "POST ${COS_ENDPOINT}"

# 把 params 里的所有字段原样转成 curl 参数：不挑字段，蒲公英返回什么就提交什么，后续增减字段无需改这里。
# 用 NUL 分隔，避免值里的空格或换行把字段截断；用 --form-string 而非 -F，避免分号截断签名。
FORM_ARGS=()
while IFS= read -r -d '' field; do
    FORM_ARGS+=(--form-string "${field}")
done < <(python3 - "${WORK_DIR}/token.json" <<'PY'
import json, sys

with open(sys.argv[1]) as fp:
    params = json.load(fp)["data"]["params"]
for key, value in params.items():
    sys.stdout.write("%s=%s\0" % (key, "" if value is None else value))
PY
)

if [ ${#FORM_ARGS[@]} -eq 0 ]; then
    echo "错误：getCOSToken 未返回任何 params 字段，无法构造上传表单" >&2
    exit 1
fi
FORM_ARGS+=(--form-string "x-cos-meta-file-name=$(basename "${APK_FILE}")")
echo "表单字段数 = $((${#FORM_ARGS[@]} / 2))（含 x-cos-meta-file-name，file 另计）"

# file 字段必须排在整个表单最后，这是 COS POST Object 的硬性要求。
UPLOAD_STAT=$(run_curl -sS \
    --connect-timeout 15 --max-time 1800 \
    -o "${WORK_DIR}/upload.log" \
    -w '%{http_code} %{time_total} %{size_upload} %{speed_upload}' \
    "${FORM_ARGS[@]}" \
    -F "file=@${APK_FILE}" \
    "${COS_ENDPOINT}" || true)
UPLOAD_HTTP_CODE=$(echo "${UPLOAD_STAT}" | awk '{print $1}')
UPLOAD_SECONDS=$(echo "${UPLOAD_STAT}" | awk '{print $2}')
UPLOAD_BYTES=$(echo "${UPLOAD_STAT}" | awk '{print $3}')
UPLOAD_SPEED=$(echo "${UPLOAD_STAT}" | awk '{print $4}')
echo "http_code=${UPLOAD_HTTP_CODE} 耗时=${UPLOAD_SECONDS}s 已传=${UPLOAD_BYTES}B 均速=${UPLOAD_SPEED}B/s"

if [ "${UPLOAD_HTTP_CODE}" != "204" ]; then
    echo "响应片段: $(head -c 300 "${WORK_DIR}/upload.log")"
    echo ""
    echo "结论：COS 上传失败。000 且耗时接近 --max-time 为超时；"
    echo "      403 Request has expired 先核对上面打印的字段长度，确认签名没被截断，再看凭证是否真的超时。"
    exit 1
fi
echo "结论：安装包已上传到 COS（204 No Content）。"
echo ""

# ---------------------------------------------------------------------------
# 第 3 步：轮询发布状态。COS 上传成功只代表文件就位，发布由蒲公英后台队列异步完成。
# ---------------------------------------------------------------------------
echo "==================== 第 3 步：轮询 buildInfo ===================="
MAX_POLLS=24
for i in $(seq 1 ${MAX_POLLS}); do
    sleep 5
    # 每轮先清空：curl 若请求失败不会覆盖该文件，否则会读到上一轮的响应。
    : > "${WORK_DIR}/info.json"
    INFO_HTTP_CODE=$(run_curl -sS -G \
        --connect-timeout 15 --max-time 60 \
        -o "${WORK_DIR}/info.json" -w '%{http_code}' \
        --data-urlencode "_api_key=${PGYER_API_KEY}" \
        --data-urlencode "buildKey=${BUILD_KEY}" \
        "https://${API_DOMAIN}/apiv2/app/buildInfo" || true)
    if [ "${INFO_HTTP_CODE}" != "200" ]; then
        echo "第 ${i}/${MAX_POLLS} 次: buildInfo HTTP ${INFO_HTTP_CODE}"
        continue
    fi
    INFO_CODE=$(json_get "${WORK_DIR}/info.json" code)
    if [ "${INFO_CODE}" = "0" ]; then
        SHORTCUT=$(json_get "${WORK_DIR}/info.json" data buildShortcutUrl)
        QRCODE=$(json_get "${WORK_DIR}/info.json" data buildQRCodeURL)
        VERSION=$(json_get "${WORK_DIR}/info.json" data buildVersion)
        echo "第 ${i}/${MAX_POLLS} 次: 发布完成"
        echo ""
        echo "==================== 验证通过 ===================="
        echo "下载短链  : https://www.pgyer.com/${SHORTCUT}"
        echo "二维码    : ${QRCODE}"
        echo "版本号    : ${VERSION}"
        echo "buildKey  : ${BUILD_KEY}"
        echo "上传耗时  : ${UPLOAD_SECONDS}s（${UPLOAD_BYTES} 字节，均速 ${UPLOAD_SPEED} B/s）"
        exit 0
    fi
    if [ "${INFO_CODE}" = "1216" ]; then
        echo "第 ${i}/${MAX_POLLS} 次: code=1216 $(json_get "${WORK_DIR}/info.json" message)"
        echo ""
        echo "结论：文件已成功送达蒲公英（说明上传链路没问题），但蒲公英解析安装包失败。"
        echo "      用假文件 / 非法 APK 测试时出现 1216 属预期结果。"
        exit 1
    fi
    echo "第 ${i}/${MAX_POLLS} 次: code=${INFO_CODE} $(json_get "${WORK_DIR}/info.json" message)"
done

echo ""
echo "结论：文件已上传成功，但 $((MAX_POLLS * 5)) 秒内未确认发布完成，buildKey=${BUILD_KEY}"
echo "      请到蒲公英后台按该 buildKey 核对。"
exit 1
