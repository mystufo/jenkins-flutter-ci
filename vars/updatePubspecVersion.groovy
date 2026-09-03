/**
 * 修改 pubspec.yaml 中的 version 行，写入 "version: <version>+<buildNumber>"。
 *
 * buildNumber 由 version 自动推算：major * 100000 + minor * 1000 + patch
 *   1.0.18  -> 100018
 *   1.1.5   -> 101005
 *   2.0.0   -> 200000
 *
 * 约束：minor < 100，patch < 1000（否则不同段会进位混淆，函数会主动报错）。
 *
 * 用法：
 *   @Library('jenkins-flutter-ci') _
 *   ...
 *   script { updatePubspecVersion(version: '1.0.18') }
 *   或：updatePubspecVersion(version: '1.0.18', pubspecPath: 'app/pubspec.yaml')
 *
 * 行为：
 *   - 只替换文件中第一处匹配 ^[\s]*version:[\s]* 的行；其它行保持不变。
 *   - 若文件不存在或未匹配到 version 行，构建会失败。
 */

// 用 @NonCPS 把正则解析隔离在 CPS 状态机之外，避免 java.util.regex.Matcher
// 作为局部变量被序列化到 Pipeline thread state，触发：
//   java.io.NotSerializableException: java.util.regex.Matcher
// 该方法只返回普通 List<Integer>（[major, minor, patch]）或 null，对 CPS 序列化是安全的。
@NonCPS
private static List<Integer> parseSemver(String version) {
    def m = version =~ /^(\d+)\.(\d+)\.(\d+)$/
    if (!m.matches()) {
        return null
    }
    return [m[0][1] as int, m[0][2] as int, m[0][3] as int]
}

def call(Map config = [:]) {
    // 用户传入的语义化版本号字符串，形如 "1.0.18"。
    def version = config.version?.toString()?.trim()
    // pubspec.yaml 路径，相对工作区或绝对路径均可，默认仓库根目录。
    def pubspecPath = config.pubspecPath?.toString()?.trim() ?: 'pubspec.yaml'

    if (!version) {
        error '缺少 version 参数：请传入 updatePubspecVersion(version: "1.0.18")'
    }

    // 校验并解析 X.Y.Z 三段。parseSemver 是 @NonCPS，返回普通 List，不会把 Matcher 泄漏到 CPS 线程状态。
    def parts = parseSemver(version)
    if (parts == null) {
        error "version 格式不合法（应为 X.Y.Z 三段非负整数）：${version}"
    }
    def major = parts[0]
    def minor = parts[1]
    def patch = parts[2]
    if (minor >= 100) {
        error "minor 段必须 < 100，否则与 major 进位冲突：${version}"
    }
    if (patch >= 1000) {
        error "patch 段必须 < 1000，否则与 minor 进位冲突：${version}"
    }

    // major*100000 + minor*1000 + patch（用户约定的推算规则）。
    def buildNumber = major * 100000 + minor * 1000 + patch
    def newLine = "version: ${version}+${buildNumber}"
    echo "更新 ${pubspecPath} -> ${newLine}"

    // 通过 withEnv 传参给 shell，避免 version / pubspecPath 含特殊字符时被 Groovy 字符串拼接破坏。
    withEnv([
        "CI_PUBSPEC_PATH=${pubspecPath}",
        "CI_PUBSPEC_NEW_LINE=${newLine}"
    ]) {
        sh '''
            set -e
            if [ ! -f "${CI_PUBSPEC_PATH}" ]; then
                echo "未找到 pubspec.yaml: ${CI_PUBSPEC_PATH}" >&2
                exit 1
            fi

            # 用 awk 原子替换：仅替换第一处 ^[\\s]*version:[\\s]* 行；
            # 用 awk -v 接收 ENV 中的新行内容，避免 sed -i 在 macOS / GNU 上行为差异。
            TMP="${CI_PUBSPEC_PATH}.$$.tmp"
            awk -v new_line="${CI_PUBSPEC_NEW_LINE}" '
                BEGIN { replaced = 0 }
                {
                    if (!replaced && $0 ~ /^[[:space:]]*version:[[:space:]]*/) {
                        print new_line
                        replaced = 1
                    } else {
                        print $0
                    }
                }
                END {
                    if (!replaced) {
                        print "未找到 version: 行，文件可能不是 pubspec.yaml" > "/dev/stderr"
                        exit 2
                    }
                }
            ' "${CI_PUBSPEC_PATH}" > "${TMP}"
            mv "${TMP}" "${CI_PUBSPEC_PATH}"

            echo "已更新版本号："
            grep -n "^[[:space:]]*version:" "${CI_PUBSPEC_PATH}" || true
        '''
    }

    // 把推算结果回写到 env，方便后续 stage 复用（如打 tag、写产物名等）。
    env.PUBSPEC_VERSION = version
    env.PUBSPEC_BUILD_NUMBER = buildNumber.toString()
    env.PUBSPEC_VERSION_FULL = "${version}+${buildNumber}"
}
