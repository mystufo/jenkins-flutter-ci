/**
 * 从指定文件读取版本号：取文件首行（去掉 \r），校验为非负整数，返回 int。
 *
 * 文件路径优先级：
 *   1. 调用时显式传入：getVersion(versionFilePath: '...')
 *   2. Jenkins 任务参数 params.version_file_path
 *
 * 路径格式（两种均支持）：
 *   1. 本地路径：'/abs/path/version.txt'
 *      → 直接在当前 agent 上读取。
 *   2. scp 风格：'user@host:/abs/path/version.txt'，例如：
 *      'root@192.168.1.100:/data/version/app_version.txt'
 *      → 先比较「本机网卡 IP」与路径里的 host：
 *        - 命中任意一个本机 IP：按本地路径直接读 host 后面的文件，避免 scp 自连接。
 *        - 不命中：用 scp 把文件拉到 agent 临时目录后再读首行。
 *
 * 用法：
 *   @Library('jenkins-flutter-ci') _
 *   ...
 *   script {
 *       def code = getVersion()
 *       echo "build code: ${code}"   // 例：123
 *   }
 *   或：def code = getVersion(versionFilePath: 'root@192.168.1.100:/.../version.txt')
 *
 * 返回：int（>= 0）
 *
 * 失败条件（构建会 error 终止）：
 *   - 未提供路径；
 *   - 文件不存在 / scp 拉取失败；
 *   - 首行不是非负整数。
 */

// 用 @NonCPS 把正则解析隔离在 CPS 状态机之外，避免 java.util.regex.Matcher
// 作为局部变量被序列化到 Pipeline thread state，触发：
//   java.io.NotSerializableException: java.util.regex.Matcher
// 该方法只返回普通 Map / null，对 CPS 序列化是安全的。
@NonCPS
private static Map parseScpSpec(String spec) {
    // user 不含 '@' 与空白；host 不含 ':' 与空白（IPv4 / 域名都满足）；path 任意非空。
    def m = spec =~ /^([^@\s]+)@([^:\s]+):(.+)$/
    if (!m.matches()) {
        return null
    }
    return [user: m[0][1], host: m[0][2], path: m[0][3]]
}

def call(Map config = [:]) {
    // 优先取调用方传入的路径，回退到任务级参数 version_file_path。
    def versionFilePath = config.versionFilePath?.toString()?.trim() ?:
                          params.version_file_path?.toString()?.trim()

    if (!versionFilePath) {
        error '缺少版本文件路径：请在 Jenkins 任务参数 version_file_path 中填写，' +
              '或调用 getVersion(versionFilePath: "/abs/path/version.txt") / ' +
              'getVersion(versionFilePath: "user@host:/abs/path/version.txt")'
    }

    // 解析 scp 风格 user@host:path；不匹配则视为纯本地路径。
    // 注意：parseScpSpec 是 @NonCPS，调用方拿到的是普通 Map，可安全跨 step 持有。
    def scpInfo = parseScpSpec(versionFilePath)

    def firstLine
    if (scpInfo != null) {
        def remoteUser = scpInfo.user
        def remoteHost = scpInfo.host
        def remotePath = scpInfo.path
        echo "解析 scp 风格路径：user=${remoteUser}, host=${remoteHost}, path=${remotePath}"

        // 通过 ENV 传入，避免路径 / spec 含特殊字符被 Groovy 字符串拼接破坏。
        withEnv([
            "CI_REMOTE_USER=${remoteUser}",
            "CI_REMOTE_HOST=${remoteHost}",
            "CI_REMOTE_PATH=${remotePath}",
            "CI_SCP_SPEC=${versionFilePath}"
        ]) {
            // 用 bash <<'EOF' 调起 bash 执行：部分 Linux Agent 的 /bin/sh 是 dash，
            // 不支持 set -o pipefail（会报 Illegal option -o pipefail，stage 直接失败）。
            // 与本仓库其它构建脚本（mr_build_*.groovy）保持一致的 heredoc 风格。
            // bash 子进程的 stdout 自然透传给外层 sh，被 returnStdout 捕获。
            firstLine = sh(
                script: '''
                    bash <<'EOF'
                        set -e
                        set -o pipefail

                        # 收集本机所有 IPv4 地址，兼容 Linux（hostname -I）与 macOS（ifconfig）。
                        LOCAL_IPS="$(hostname -I 2>/dev/null || true)"
                        if [ -z "$LOCAL_IPS" ]; then
                            LOCAL_IPS="$(ifconfig 2>/dev/null | awk '/inet / {print $2}' | sed 's/addr://g' || true)"
                        fi

                        IS_LOCAL=0
                        for ip in $LOCAL_IPS; do
                            if [ "$ip" = "$CI_REMOTE_HOST" ]; then
                                IS_LOCAL=1
                                break
                            fi
                        done

                        if [ "$IS_LOCAL" = "1" ]; then
                            # 本机即是 host，直接按本地路径读取，避免 scp 自连接。
                            echo "命中本机 IP（${CI_REMOTE_HOST}），直接读取本地文件：${CI_REMOTE_PATH}" >&2
                            if [ ! -f "$CI_REMOTE_PATH" ]; then
                                echo "未找到版本文件（本机直接读取）: $CI_REMOTE_PATH" >&2
                                exit 1
                            fi
                            head -n 1 "$CI_REMOTE_PATH" | tr -d '\\r'
                        else
                            # 远端机器：用 ssh 远程执行 head -n 1 直读，避免 scp 临时文件方案
                            # 在 macOS agent 的 /var/folders 沙盒目录上偶发触发 "cannot find current thread"。
                            # 路径用单引号包裹在远端 shell 里展开（要求 path 不含单引号字符，版本文件路径正常情况下不会有）。
                            echo "本机 IP 列表 [${LOCAL_IPS}] 未命中 ${CI_REMOTE_HOST}，使用 ssh 远端读取：${CI_SCP_SPEC}" >&2
                            # BatchMode=yes：禁止交互式询问密码 / 确认 known_hosts，CI 上必须。
                            ssh -q \
                                -o StrictHostKeyChecking=no \
                                -o UserKnownHostsFile=/dev/null \
                                -o BatchMode=yes \
                                -o ConnectTimeout=15 \
                                "${CI_REMOTE_USER}@${CI_REMOTE_HOST}" \
                                "head -n 1 -- '${CI_REMOTE_PATH}'" | tr -d '\\r'
                        fi
EOF
                ''',
                returnStdout: true
            ).trim()
        }
    } else {
        // 兼容旧用法：纯本地路径。
        withEnv(["CI_VERSION_FILE_PATH=${versionFilePath}"]) {
            firstLine = sh(
                script: '''
                    set -e
                    if [ ! -f "${CI_VERSION_FILE_PATH}" ]; then
                        echo "未找到版本文件: ${CI_VERSION_FILE_PATH}" >&2
                        exit 1
                    fi
                    head -n 1 "${CI_VERSION_FILE_PATH}" | tr -d '\\r'
                ''',
                returnStdout: true
            ).trim()
        }
    }

    if (!(firstLine ==~ /\d+/)) {
        error "版本文件 ${versionFilePath} 首行不是非负整数：[${firstLine}]"
    }

    def versionInt = firstLine as int
    echo "读取版本号: ${versionInt}（来自 ${versionFilePath}）"
    return versionInt
}
