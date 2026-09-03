/**
 * 将单个产物文件从本地拷贝到目标位置（自动判断本机 / 远程），统一使用 cp / scp 拷贝语义（始终保留源文件）。
 *
 * 设计为「纯传输原语」：只负责把一个文件从 sourcePath 搬到 targetPath，
 * 所有命名 / 时间戳 / 版本号 / 跳过等业务逻辑都由调用方在外部准备好后传入。
 *
 * 行为：
 *   - 当前机器即目标主机（hostname -I 含 remoteHost 解析出的 IP）时，走本地 cp；
 *   - 否则通过 ssh 建目录 + scp 拷贝到远程主机；
 *   - 自动创建 targetPath 所在目录；
 *   - 单条拷贝命令超过 timeoutSeconds（默认 300 秒 / 5 分钟）即中断，并使本步骤失败退出。
 *
 * 用法：
 *   copyArtifactToRemote(
 *       sourcePath: 'build.apk',                              // 必填：本地源文件路径
 *       targetPath: '/data/build/app/.../x.apk'              // 必填：目标完整文件路径
 *   )
 *   // 可选覆盖默认值：
 *   copyArtifactToRemote(sourcePath: a, targetPath: b, remoteHost: 'root@1.2.3.4', timeoutSeconds: 600)
 *
 *   remoteHost 未传时取流水线 environment 里的 ARTIFACT_HOST（形如 user@host），两者都为空则报错。
 *
 * 返回：targetPath（便于调用方继续拼 env.REMOTE_*_PATH）。
 */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(Map config = [:]) {
    String sourcePath = (config.sourcePath ?: '').toString().trim()
    String targetPath = (config.targetPath ?: '').toString().trim()
    String remoteHost = (config.remoteHost ?: env.ARTIFACT_HOST ?: '').toString().trim()
    int timeoutSeconds = (config.timeoutSeconds ?: 300) as int

    if (!sourcePath) {
        error '[copyArtifactToRemote] sourcePath 不能为空'
    }
    if (!targetPath) {
        error '[copyArtifactToRemote] targetPath 不能为空'
    }
    if (!fileExists(sourcePath)) {
        error "[copyArtifactToRemote] 源文件不存在: ${sourcePath}"
    }
    if (!remoteHost) {
        error '[copyArtifactToRemote] 缺少目标主机：请传入 remoteHost 或在流水线 environment 中设置 ARTIFACT_HOST'
    }

    // targetPath 所在目录，用于先行创建（无 '/' 时退化为当前目录）。
    String targetDir = targetPath.contains('/') ? targetPath.substring(0, targetPath.lastIndexOf('/')) : '.'

    // 从 remoteHost 中解析出主机地址（去掉 user@ 前缀），与本机 IP 列表比对判断是否本机。
    String remoteIp = remoteHost.contains('@') ? remoteHost.substring(remoteHost.indexOf('@') + 1) : remoteHost
    String currentIps = sh(script: 'hostname -I 2>/dev/null || true', returnStdout: true).trim()
    boolean isLocal = (currentIps.split(/\s+/) as List).contains(remoteIp)

    // 用 Jenkins 内置 timeout 步骤包裹，跨平台（macOS 无 GNU timeout 命令）。
    // 关键：timeout 超时与「人工手动取消」都会抛 FlowInterruptedException，且都会把构建判为 ABORTED，
    // 单看 currentBuild.result 无法区分。这里通过中断原因(cause)区分二者：
    //   - 超时(ExceededTimeout)：转成 error(FAILURE)，让调用方 post { failure } 能触发失败通知；
    //   - 人工取消(UserInterruption 等)：原样抛出，保持 ABORTED，不触发失败通知。
    try {
        timeout(time: timeoutSeconds, unit: 'SECONDS') {
            if (isLocal) {
                echo "[copyArtifactToRemote] 本机即目标主机(${remoteIp})，本地 cp: ${sourcePath} -> ${targetPath}（超时 ${timeoutSeconds}s）"
                sh """
                    mkdir -p ${targetDir}
                    cp ${sourcePath} ${targetPath}
                """
            } else {
                echo "[copyArtifactToRemote] 远程 scp 到 ${remoteHost}:${targetPath}（超时 ${timeoutSeconds}s）"
                sh """
                    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=15 ${remoteHost} 'mkdir -p ${targetDir}'
                    scp -o StrictHostKeyChecking=no -o ConnectTimeout=15 ${sourcePath} ${remoteHost}:${targetPath}
                """
            }
        }
    } catch (FlowInterruptedException e) {
        // e.causes 为中断原因列表；类名含 ExceededTimeout 即为 timeout 步骤超时。
        // 用类名字符串匹配而非直接 import，避免不同 Jenkins 版本内部类路径差异导致 NoClassDefFound。
        boolean causedByTimeout = e.causes.any { it.getClass().getName().contains('ExceededTimeout') }
        if (causedByTimeout) {
            error "[copyArtifactToRemote] 拷贝超时（>${timeoutSeconds}s，判为构建失败）：${sourcePath} -> ${targetPath}"
        }
        // 非超时（如人工手动取消）：保持 ABORTED，原样抛出。
        throw e
    }

    echo "[copyArtifactToRemote] 拷贝完成: ${targetPath}"
    return targetPath
}
