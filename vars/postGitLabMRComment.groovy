/**
 * 在触发当前构建的 GitLab MR 上追加一条 comment，用于把"产物服务器存储路径 / 下载链接 / 构建日志"
 * 等信息回写到 MR 讨论区，方便 reviewer 直接拿到包测试。
 *
 * 实现：依赖 Jenkins GitLab Plugin 提供的 addGitLabMRComment(comment: ...) 步骤；
 * 该步骤会自动复用 Pipeline 顶部 triggers { gitlab(...) } 关联的 GitLab connection
 * 与 token，无需在脚本里再注入凭据。
 *
 * 适用前提：
 *   - 当前构建是由 GitLab Webhook（Merge Request / Note）触发，
 *     此时 env.gitlabMergeRequestIid 非空；
 *   - 手动 Build with Parameters / 定时触发时无 MR 上下文，本步骤会自动跳过。
 *
 * 使用：
 *   postGitLabMRComment(
 *       platform     : 'Android',                // 必填：用于 comment 标题展示
 *       status       : 'success',                // success / failure，影响标题前缀
 *       artifactPath : env.REMOTE_APK_PATH,      // 远程产物相对路径，可空
 *       sourceMapPath: env.REMOTE_SOURCEMAP_PATH, // 远程 sourceMap 路径，可空
 *       pgyerUrl     : env.PGYER_URL,            // 蒲公英下载地址，可空
 *       pgyerError   : env.PGYER_UPLOAD_ERROR,   // 蒲公英失败说明，可空
 *       extraNote    : '可选的额外 markdown 文本'
 *       appVersion   : '应用版本号（可选）；未传时自动读取 env.APP_VERSION'
 *   )
 */
def call(Map config = [:]) {
    // 解析入参，全部带兜底默认值，避免空值拼接出 "null" 字样的 comment。
    String platform      = (config.platform ?: '').toString().trim()
    String status        = (config.status ?: 'success').toString().trim().toLowerCase()
    String artifactPath  = (config.artifactPath ?: '').toString().trim()
    String sourceMapPath = (config.sourceMapPath ?: '').toString().trim()
    String pgyerUrl      = (config.pgyerUrl ?: '').toString().trim()
    String pgyerError    = (config.pgyerError ?: '').toString().trim()
    String buildUrl      = (config.buildUrl ?: env.BUILD_URL ?: '').toString().trim()
    String extraNote     = (config.extraNote ?: '').toString().trim()
    // 应用版本号：流水线可在调用处传入；默认复用「读取版本号」阶段写入的 env.APP_VERSION。
    String appVersion    = (config.appVersion ?: env.APP_VERSION ?: '').toString().trim()

    // 仅在 GitLab Webhook（MR / Note）触发时才有 MR 上下文，
    // 手动触发或定时触发拿不到 iid，直接跳过即可。
    String mrIid = (env.gitlabMergeRequestIid ?: '').toString().trim()
    if (!mrIid || mrIid == 'null') {
        echo "[postGitLabMRComment] 非 MR Webhook 触发（gitlabMergeRequestIid 为空），跳过 GitLab MR 评论"
        return
    }

    boolean isFailure = (status == 'failure' || status == 'failed')
    String emoji      = isFailure ? '❌' : '✅'
    String statusText = isFailure ? '构建失败' : '构建完成'
    String platformLabel = platform ? platform : '应用'
    String jobLabel = "${env.JOB_NAME ?: 'Jenkins Job'} #${env.BUILD_NUMBER ?: '?'}"

    // 拼装 markdown comment：标题一行 + 列表项若干，缺失字段自动跳过。
    List<String> lines = []
    lines << "${emoji} **${platformLabel} ${statusText}** （${jobLabel}）"

    if (env.MR_AUTHOR?.trim()) {
        lines << "- 提交者：${env.MR_AUTHOR}"
    }
    if (env.MR_SOURCE_BRANCH?.trim() || env.MR_TARGET_BRANCH?.trim()) {
        lines << "- 分支：${env.MR_SOURCE_BRANCH ?: '-'} → ${env.MR_TARGET_BRANCH ?: '-'}"
    }
    if (appVersion) {
        lines << "- 应用版本号：${appVersion}"
    }
    if (artifactPath) {
        lines << "- 远程产物路径：`${artifactPath}`"
    }
    if (sourceMapPath) {
        lines << "- sourceMap 路径：`${sourceMapPath}`"
    }
    if (pgyerUrl) {
        lines << "- 蒲公英下载：${pgyerUrl}"
    }
    if (pgyerError) {
        lines << "- 蒲公英说明：${pgyerError}"
    }
    if (buildUrl) {
        lines << "- 构建日志：${buildUrl}"
    }
    if (extraNote) {
        lines << ''
        lines << extraNote
    }

    String comment = lines.join('\n')

    // 任何 GitLab 通信异常都不应阻断构建主流程，仅打印警告即可。
    try {
        addGitLabMRComment(comment: comment)
        echo "[postGitLabMRComment] 已向 GitLab MR (iid=${mrIid}) 提交评论"
    } catch (Exception e) {
        echo "[postGitLabMRComment] 提交 GitLab MR 评论失败（不阻断流水线）：${e.message}"
    }
}
