/**
 * 流水线脚本编译失败时补发飞书（以及可选的 GitLab MR 评论）。
 *
 * Jenkins 会先编译 Jenkinsfile；若编译失败，Declarative 的 post { failure } 根本不会执行。
 * 入口脚本必须始终能通过编译，再用 evaluate(readTrusted(...)) 加载真正的 pipeline {}，
 * 在 catch 里调用本方法。
 *
 * 仅在判定为「脚本编译/解析失败」时发通知，避免普通阶段失败与内层 post { failure } 重复推送。
 *
 * 用法（见仓库根目录 mr_build_android.groovy / mr_build_ios.groovy 入口）：
 *   notifyPipelineCompileFailure(
 *       err                 : err,
 *       webhookCredentialsId: 'feishu-webhook',   // 飞书 Webhook 的 Secret text 凭据 ID，缺省 'feishu-webhook'
 *       failureTitle        : 'Android APK 构建失败',
 *       platform            : 'Android'           // 非空时同时回写 GitLab MR 评论
 *   )
 */
def call(Map config = [:]) {
    def err = config.err
    String webhookCredentialsId = (config.webhookCredentialsId ?: 'feishu-webhook').toString().trim()
    String failureTitle = (config.failureTitle ?: '构建失败').toString().trim()
    String platform = (config.platform ?: '').toString().trim()

    if (!err) {
        echo '[notifyPipelineCompileFailure] 未传入 err，跳过'
        return
    }
    if (!isCompileError(err)) {
        echo "[notifyPipelineCompileFailure] 非脚本编译失败（${err.getClass().name}），跳过以免与 post { failure } 重复通知"
        return
    }

    echo "[notifyPipelineCompileFailure] 流水线脚本编译失败，补发飞书 · JOB_NAME=${env.JOB_NAME ?: '(空)'} · BUILD_URL=${env.BUILD_URL ?: '(空)'}"

    String errMsg = collectErrorText(err)
    if (errMsg.length() > 2500) {
        errMsg = errMsg.substring(0, 2500) + '\n...(已截断)'
    }

    String author = firstNonBlank(env.gitlabUserName, env.gitlabActor, env.gitlabUserUsername)
    String sourceBranch = firstNonBlank(env.gitlabSourceBranch, env.MR_SOURCE_BRANCH)
    String targetBranch = firstNonBlank(env.gitlabTargetBranch, env.MR_TARGET_BRANCH)
    String mrIid = firstNonBlank(env.gitlabMergeRequestIid)
    String mrUrl = buildMrUrl(mrIid)

    List<String> parts = []
    parts << "**任务：** ${env.JOB_NAME ?: '-'} #${env.BUILD_NUMBER ?: '?'}"
    if (author) {
        parts << "**提交者：** ${author}"
    }
    if (sourceBranch || targetBranch) {
        parts << "**分支：** ${sourceBranch ?: '-'} → ${targetBranch ?: '-'}"
    }
    if (mrUrl) {
        parts << "**MR 地址：** [${mrUrl}](${mrUrl})"
    } else if (mrIid && mrIid != 'null') {
        parts << "**MR：** !${mrIid}"
    }
    parts << '**失败原因：** 流水线脚本无法编译，`post { failure }` 未执行。'
    parts << "```\n${errMsg}\n```"
    String content = parts.join('\n\n')

    def card = [
        header: [
            title: [
                tag: 'plain_text',
                content: "❌ ${failureTitle}"
            ],
            template: 'red'
        ],
        elements: [
            [
                tag: 'markdown',
                content: content
            ],
            [
                tag: 'action',
                actions: [
                    [
                        tag: 'button',
                        text: [
                            tag: 'plain_text',
                            content: '查看构建日志'
                        ],
                        url: env.BUILD_URL ?: '',
                        type: 'primary'
                    ]
                ]
            ]
        ]
    ]

    // 入口脚本运行在 node 之外，sendFeishuCard 需要工作区来写文件与执行 curl。
    node {
        sendFeishuCard(card: card, credentialsId: webhookCredentialsId)
    }
    echo '飞书失败通知已发送（脚本编译失败兜底）'

    if (platform) {
        try {
            postGitLabMRComment(
                platform : platform,
                status   : 'failure',
                extraNote: '流水线脚本编译失败，Jenkins 未能进入 post { failure }；详见构建日志。'
            )
        } catch (commentErr) {
            echo "[notifyPipelineCompileFailure] 回写 GitLab MR Comment 失败：${commentErr}"
        }
    }
}

@NonCPS
def isCompileError(err) {
    String msg = collectErrorText(err)
    return msg.contains('MultipleCompilationErrorsException') ||
        msg.contains('CpsCompilationErrorsException') ||
        msg.contains('CompilationFailedException') ||
        msg.contains('startup failed:') ||
        msg.contains('unexpected char:') ||
        msg.contains('Unexpected character')
}

@NonCPS
def collectErrorText(err) {
    List<String> chunks = []
    def cur = err
    int depth = 0
    while (cur != null && depth < 8) {
        chunks << "${cur.getClass().name}: ${cur.toString()}"
        try {
            cur = cur.getCause()
        } catch (ignored) {
            break
        }
        depth += 1
    }
    return chunks.join('\n')
}

def firstNonBlank(a = null, b = null, c = null) {
    for (v in [a, b, c]) {
        String s = (v ?: '').toString().trim()
        if (s && s != 'null') {
            return s
        }
    }
    return ''
}

def buildMrUrl(String mrIid) {
    if (!mrIid || mrIid == 'null') {
        return firstNonBlank(env.MR_URL)
    }
    String homepage = firstNonBlank(env.gitlabSourceRepoHomepage, env.gitlabTargetRepoHttpUrl)
    if (!homepage) {
        return firstNonBlank(env.MR_URL)
    }
    // 与 gitlabMrCheckout 一致：内网主页地址按配置替换为对外地址（入口脚本运行在 pipeline 之外，
    // 此时 environment {} 尚未生效，这两个变量需在 Jenkins 全局 / 节点环境变量里配置才会生效）。
    String internalUrl = firstNonBlank(env.GITLAB_INTERNAL_URL)
    String publicUrl = firstNonBlank(env.GITLAB_PUBLIC_URL)
    if (internalUrl && publicUrl) {
        homepage = homepage.replace(internalUrl, publicUrl)
    }
    if (homepage.endsWith('/')) {
        homepage = homepage.substring(0, homepage.length() - 1)
    }
    return "${homepage}/-/merge_requests/${mrIid}"
}
