/**
 * GitLab MR 构建：检出目标仓库、合并源分支，并设置 env（MR_COMMIT、TARGET_COMMIT 等）。
 *
 * Jenkins 配置（Global Pipeline Library）：
 * 1. Manage Jenkins -> System -> Global Pipeline Libraries -> Add
 * 2. Name：与 pipeline 顶部的 @Library('此处名称') 一致，例如 jenkins-flutter-ci
 * 3. Default version：main 或 master（与仓库默认分支一致）
 * 4. Retrieval method：Git，Repository URL：本仓库地址
 * 5. 勾选「Allow default version to be overridden」可按分支构建
 *
 * 用法：
 *   @Library('jenkins-flutter-ci') _
 *   ...
 *   script { gitlabMrCheckout() }
 *   或：gitlabMrCheckout(credentialsId: 'your-id')
 *   手动触发未填 repo_url 时：gitlabMrCheckout(defaultRepoUrl: 'git@host:group/repo.git')
 *   无 GitLab 目标分支时默认检出分支：gitlabMrCheckout(defaultBranch: 'release/v1.1.0')
 *
 * 可从流水线 environment 读取的配置（调用参数优先）：
 *   REPO_URL             defaultRepoUrl 的默认值
 *   GIT_CREDENTIALS_ID   credentialsId 的默认值（未设置时 'gitlab-ssh-key'；设为空串则不使用凭据，依赖构建机自身的免密 ssh）
 *   GITLAB_INTERNAL_URL / GITLAB_PUBLIC_URL
 *                        Webhook 注入的仓库主页若是内网地址，生成 MR 链接时把前者替换为后者；任一为空则不替换
 *
 * 触发方式（优先级从高到低）：
 *   1. GitLab Webhook（MR / Push）：插件注入 gitlab* 环境变量，自动识别源/目标分支。
 *   2. 按 commit 手动触发：填写 source_commit + target_commit。
 *   3. 按分支手动触发（类 MR 编译）：非 webhook 且未填 commit 时，填写 source_branch + target_branch，
 *      会检出 target_branch 并把 source_branch 合并进来后编译；可选填 repo_url 覆盖默认仓库。
 *   4. 仅编译单分支：只填 target_branch（不填 source_branch）时，源分支与目标对齐，merge 为 no-op。
 */

def call(Map config = [:]) {
    // 凭据 ID：显式传入 / environment 里设置的值优先；两者都未设置才用默认值。设为空串表示不使用凭据。
    def credentialsId = (config.containsKey('credentialsId') ? config.credentialsId
        : (env.GIT_CREDENTIALS_ID != null ? env.GIT_CREDENTIALS_ID : 'gitlab-ssh-key'))?.toString()?.trim() ?: ''
    // 手动未填 repo_url、或非 MR Webhook 触发且未注入 gitlab* 环境变量时，用此默认地址
    def defaultRepoUrl = (config.defaultRepoUrl ?: env.REPO_URL ?: '').toString().trim()
    // 未注入 gitlabTargetBranch、未填 target_branch 时的检出分支
    def defaultBranch = config.defaultBranch?.toString()?.trim()

    // 手动触发方式一：填写了 source_commit + target_commit，直接按 commit 触发（历史行为）。
    def isManualCommit = params.source_commit && params.source_commit != 'null' &&
                         params.target_commit && params.target_commit != 'null'

    // 是否为 GitLab Webhook（MR / Push）触发：这些事件会由插件注入 gitlab* 环境变量。
    // 用它排除 webhook 场景，避免 Jenkins 参数缓存里残留的 source_branch/target_branch 干扰 webhook 构建。
    def isWebhook = (env.gitlabTargetBranch?.trim() && env.gitlabTargetBranch != 'null') ||
                    (env.gitlabSourceBranch?.trim() && env.gitlabSourceBranch != 'null') ||
                    (env.gitlabMergeRequestIid?.trim() && env.gitlabMergeRequestIid != 'null')

    // 手动触发方式二：非 webhook、未填 commit，但填写了 source_branch + target_branch，
    // 按「分支」触发一次类 MR 编译（检出 target_branch，再把 source_branch 合并进来）。
    def isManualBranch = !isManualCommit && !isWebhook &&
                         params.source_branch?.trim() && params.source_branch != 'null' &&
                         params.target_branch?.trim() && params.target_branch != 'null'

    // 历史代码里 isManual 均指「按 commit 手动触发」，保留该语义不变。
    def isManual = isManualCommit

    def targetRepoUrl
    def sourceRepoUrl
    def branch

    if (isManualCommit || isManualBranch) {
        targetRepoUrl = params.repo_url?.trim() ?: defaultRepoUrl
        sourceRepoUrl = targetRepoUrl
        branch = params.target_branch?.trim() ?: defaultBranch ?: 'main'
    } else {
        targetRepoUrl = env.gitlabTargetRepoSshUrl ?: env.gitlabSourceRepoSshUrl ?: params.repo_url?.trim() ?: defaultRepoUrl
        sourceRepoUrl = env.gitlabSourceRepoSshUrl
        branch = env.gitlabTargetBranch ?: params.target_branch?.trim() ?: defaultBranch ?: 'main'
    }

    if (!targetRepoUrl) {
        error '缺少仓库地址：请在任务参数中填写 repo_url，在流水线 environment 中设置 REPO_URL，或调用 gitlabMrCheckout(defaultRepoUrl: "git@host:group/repo.git")'
    }
    echo "检出仓库: ${targetRepoUrl}"

    // 每次检出前彻底清空工作区（含 .git 与隐藏文件），避免残留导致 clone 不全或状态异常。
    def ws = env.WORKSPACE
    if (ws) {
        dir(ws) {
            sh '''
                set +e
                rm -rf .git
                rm -rf ./*
                rm -rf .[!.]* 2>/dev/null
            '''
            deleteDir()
        }
    } else {
        deleteDir()
    }

    checkout([
        $class           : 'GitSCM',
        branches         : [[name: "${branch}"]],
        extensions       : [
            [$class: 'CloneOption', shallow: false],
        ],
        userRemoteConfigs: [credentialsId
            ? [url: targetRepoUrl, credentialsId: credentialsId]
            : [url: targetRepoUrl]]
    ])

    if (!isManual && sourceRepoUrl != targetRepoUrl && env.gitlabSourceBranch) {
        sh "git remote add source ${sourceRepoUrl}"
        sh "git fetch source ${env.gitlabSourceBranch}"
    }

    sh 'git fetch origin --unshallow 2>/dev/null || true'

    // MR 触发时 Jenkins Git 可能检出 gitlabMergeRequestLastCommit（源分支 tip），而非目标分支
    // origin/<branch> 的 tip。此时 HEAD 已是 MR_COMMIT，后续 git merge MR_COMMIT 会显示
    // Already up to date，并未在「当前目标分支最新」上合并源。强制对齐远端目标分支后再合并。
    if (!isManual) {
        // 分支名通过 withEnv 传入，shell 内用 "${VAR}" 展开，避免在 Groovy 里拼引号时
        // 分支名含单引号、反斜杠等导致命令被截断或注入风险。
        withEnv(["CI_MR_TARGET_BRANCH=${branch}"]) {
            sh '''
                git fetch origin "${CI_MR_TARGET_BRANCH}"
                git reset --hard "origin/${CI_MR_TARGET_BRANCH}"
            '''
        }
    }

    if (isManual) {
        env.MR_COMMIT = params.source_commit.trim()
        env.TARGET_COMMIT = params.target_commit.trim()
        echo '使用手动指定的 Commit：'
    } else if (isManualBranch) {
        // 按分支手动触发：目标分支 tip 作基准提交（前面已 reset --hard origin/branch）；
        // 把源分支拉到本地后取其 tip 作被合并提交，供后续 git log / merge 使用。
        env.TARGET_COMMIT = sh(
            script: "git rev-parse origin/${branch}",
            returnStdout: true
        ).trim()
        withEnv(["CI_MR_SOURCE_BRANCH=${params.source_branch.trim()}"]) {
            sh 'git fetch origin "${CI_MR_SOURCE_BRANCH}"'
        }
        env.MR_COMMIT = sh(
            script: 'git rev-parse FETCH_HEAD',
            returnStdout: true
        ).trim()
        echo "按分支手动触发：源分支 ${params.source_branch.trim()} → 目标分支 ${branch}"
    } else {
        def mrCommitFromPlugin = env.gitlabMergeRequestLastCommit
        env.MR_COMMIT = mrCommitFromPlugin
        // 手动触发、Replay 等非 MR Webhook 场景下插件不会注入 gitlabMergeRequestLastCommit，此时用当前 HEAD（已与 origin/目标分支对齐）
        if (!env.MR_COMMIT?.trim() || env.MR_COMMIT == 'null') {
            env.MR_COMMIT = sh(
                script: 'git rev-parse HEAD',
                returnStdout: true
            ).trim()
            echo '未注入 gitlabMergeRequestLastCommit，使用 HEAD 作为 MR_COMMIT（非 MR Webhook / 手动仅编译当前分支）'
        }
        // Push 事件：插件将 lastCommit 设为 after；用 before 作基准才能看到本次推送的 log/diff
        def noCommitSha = '0000000000000000000000000000000000000000'
        if (env.gitlabActionType == 'PUSH' && env.gitlabBefore && env.gitlabBefore != noCommitSha) {
            env.TARGET_COMMIT = env.gitlabBefore
        } else {
            env.TARGET_COMMIT = sh(
                script: "git rev-parse origin/${branch}",
                returnStdout: true
            ).trim()
        }
        if (mrCommitFromPlugin?.trim() && mrCommitFromPlugin != 'null') {
            echo '使用 GitLab Webhook 提供的 MR 提交：'
        } else {
            echo '当前构建 Commit（无 MR Webhook 时的回退）：'
        }
    }

    if (!isManual && env.gitlabSourceRepoHomepage && env.gitlabMergeRequestIid) {
        def homepage = env.gitlabSourceRepoHomepage
        // Webhook 注入的主页可能是内网地址，按 environment 配置替换成对外可访问的地址
        String internalUrl = (env.GITLAB_INTERNAL_URL ?: '').trim()
        String publicUrl = (env.GITLAB_PUBLIC_URL ?: '').trim()
        if (internalUrl && publicUrl) {
            homepage = homepage.replace(internalUrl, publicUrl)
        }
        env.MR_URL = "${homepage}/-/merge_requests/${env.gitlabMergeRequestIid}"
        echo "  MR 地址: ${env.MR_URL}"
    }
    echo "  Source Commit: ${env.MR_COMMIT}"
    echo "  Target Commit: ${env.TARGET_COMMIT}"

    env.COMMIT_MESSAGES = sh(
        script: "git log --format='%h %s' ${env.TARGET_COMMIT}..${env.MR_COMMIT}",
        returnStdout: true
    ).trim()
    echo "Commit 说明:\n${env.COMMIT_MESSAGES}"

    env.MR_AUTHOR = sh(
        script: "git log -1 --format='%an' ${env.MR_COMMIT}",
        returnStdout: true
    ).trim()
    echo "MR 提交者: ${env.MR_AUTHOR}"

    env.MR_SOURCE_BRANCH = (env.gitlabSourceBranch ?: params.source_branch ?: '').toString()
    env.MR_TARGET_BRANCH = (env.gitlabTargetBranch ?: params.target_branch ?: branch ?: 'main').toString()
    // 非 Webhook 且无手动 source_branch：仅构建当前检出分支，源与目标用同一分支名（fetch/merge 为 no-op）
    if (!env.MR_SOURCE_BRANCH?.trim() && !isManual) {
        env.MR_SOURCE_BRANCH = branch
        echo "未注入 gitlabSourceBranch / source_branch，源分支与目标对齐为 ${branch}（非 MR 触发、仅编译）"
    }
    echo "源分支: ${env.MR_SOURCE_BRANCH}，目标分支: ${env.MR_TARGET_BRANCH}"

    if (!env.MR_SOURCE_BRANCH?.trim()) {
        error '无法执行合并：缺少源分支信息（手动触发时请填写 source_branch）'
    }

    def fetchOk = true
    if (sourceRepoUrl == targetRepoUrl) {
        // 先用 git ls-remote 探测源分支是否仍存在于远端：
        //   exit 0  -> 存在，正常 fetch（绝大多数 MR 进行中场景走这条）
        //   exit 2  -> ref 不存在（典型场景：MR 合并时勾了「移除源分支」），跳过 fetch 直接走 MR ref fallback，避免日志里出现 fatal: couldn't find remote ref 的红字误导
        //   其它    -> 网络/权限异常，跳过 fetch 直接走 MR ref fallback
        // 通过 withEnv 把分支名传给 shell，避免分支名含特殊字符时被字符串拼接破坏。
        def lsRemoteRc = -1
        withEnv(["CI_MR_SOURCE_BRANCH=${env.MR_SOURCE_BRANCH}"]) {
            lsRemoteRc = sh(
                script: 'git ls-remote --exit-code --heads origin "${CI_MR_SOURCE_BRANCH}" >/dev/null 2>&1',
                returnStatus: true
            )
        }
        if (lsRemoteRc == 0) {
            // ls-remote 认为分支存在，尝试常规 fetch。
            // 注意：fetch 可能仍然以 "couldn't find remote ref" 失败——这是 GitLab 在
            // 「合并 + 删除源分支」与 webhook 触发之间窗口期内 ls-remote / fetch 结果
            // 不同步导致的，并非真的网络错误。下方统一走 MR ref fallback 即可。
            fetchOk = sh(script: "git fetch origin ${env.MR_SOURCE_BRANCH}", returnStatus: true) == 0
            if (!fetchOk) {
                echo "git fetch origin ${env.MR_SOURCE_BRANCH} 失败（ls-remote 之后源分支可能被删除，或服务端 ref 与 fetch 协议层短暂不同步）。"
            }
        } else if (lsRemoteRc == 2) {
            echo "源分支 ${env.MR_SOURCE_BRANCH} 已不在远端（很可能 MR 合并时移除了源分支）。"
            fetchOk = false
        } else {
            echo "git ls-remote 返回 rc=${lsRemoteRc}（非 0/2），可能为网络或权限异常。"
            fetchOk = false
        }
        // 任何 fetch 失败的情况只要还有 MR iid，就用 refs/merge-requests/<iid>/head 兜底：
        // GitLab 即便已删除源分支，这个服务端 ref 仍然保留，能稳定取到 MR 的 head 提交。
        if (!fetchOk && env.gitlabMergeRequestIid) {
            echo "改用 MR ref: refs/merge-requests/${env.gitlabMergeRequestIid}/head"
            fetchOk = sh(
                script: "git fetch origin refs/merge-requests/${env.gitlabMergeRequestIid}/head:refs/remotes/origin/mr-${env.gitlabMergeRequestIid}",
                returnStatus: true
            ) == 0
        }
    }
    if (!fetchOk) {
        error '无法获取源分支提交：分支可能已被删除。若为 MR 触发，请确保 MR 未被关闭。'
    }
    sh "git merge ${env.MR_COMMIT} --no-edit"
    echo '已将 source 分支合并到目标分支'

    sh "git diff ${env.TARGET_COMMIT} ${env.MR_COMMIT} > mr_diff.txt"
    echo 'Diff 文件已生成: mr_diff.txt'
}
