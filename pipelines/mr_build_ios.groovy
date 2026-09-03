@Library('jenkins-flutter-ci') _

// 本文件由仓库根目录对应入口 mr_build_ios.groovy 通过 evaluate 加载。
// Jenkins Job 的 Script Path 请保持指向根目录入口，以便脚本编译失败时仍能发飞书。

pipeline {
    agent any

    // ============================ 项目配置（按需修改） ============================
    // 与部署环境相关的常量集中在这里。凭据类信息（Git SSH Key、飞书 Webhook）不放在源码里，
    // 统一走 Jenkins 凭据，这里只填凭据 ID。
    // 注意：Declarative 的 triggers {} 块无法引用 environment，GitLab secretToken 请直接改下方 triggers。
    environment {
        // 默认检出的仓库地址（GitLab Webhook 触发时以 Webhook 注入的地址为准；手动触发可用 repo_url 参数覆盖）
        REPO_URL = 'git@gitlab.example.com:group/your-flutter-app.git'
        // 拉取代码用的凭据 ID（类型：SSH Username with private key）；构建机已免密 ssh 到 GitLab 时设为 ''
        GIT_CREDENTIALS_ID = 'gitlab-ssh-key'
        // 飞书机器人 Webhook 的凭据 ID（类型：Secret text，值为完整 webhook URL）
        FEISHU_WEBHOOK_CREDENTIALS_ID = 'feishu-webhook'
        // 产物服务器（user@host）与产物根目录；构建机本身就是产物服务器时会自动改用本地 cp
        ARTIFACT_HOST = 'root@192.168.1.100'
        ARTIFACT_ROOT_DIR = '/data/build/your-flutter-app'
        // Flutter SDK 目录（为空则直接使用 PATH 里的 flutter）
        FLUTTER_SDK_DIR = '/opt/flutter'
        // ExportOptions.plist 所在目录：distribute_type=testFlight 时读取 <目录>/testFlight/ExportOptions.plist，
        // distribute_type=app 时读取 <目录>/appStore/ExportOptions.plist
        IOS_EXPORT_OPTIONS_DIR = '/opt/ci/ios'
        // GitLab Webhook 注入的仓库主页若是内网地址，生成 MR 链接时把前者替换为后者（任一为空则不替换）
        GITLAB_INTERNAL_URL = ''
        GITLAB_PUBLIC_URL = ''
    }

    // 以下参数请在 Jenkins 任务「参数化构建过程」里手动添加（勿在脚本里声明 parameters 默认值）：
    // env（字符串，prod / pre / dev，用于选择 flutter build ipa --dart-define-from-file 的配置文件，未配置或非法值回退 config/prod.json）、
    // ipaFile（可选，直接指定 IPA 路径；为空则取 build/ios/ipa/ 下最新的 *.ipa）、
    // distribute_type（testFlight=执行 xcodebuild -exportArchive 并使用 testFlight plist；app=同上但使用 appStore plist；其它值跳过 export）、
    // export_method（flutter build ipa 的 --export-method，如 development / ad-hoc / app-store，未填默认 development）、
    // is_force_build（true=强制完整构建，未配置或 false=按自动模式：与上次成功构建为同一提交则跳过）、
    // version_pre（支持两种模式：1) 完整版本号 X.Y.Z 如 1.0.33 直接使用；2) 版本前缀 X.Y. / X.Y 与 getVersion() 读出的 versionCode 拼接为 X.Y.Z 后写回 pubspec.yaml）、
    // version_file_path（版本号文件路径，支持本地路径或 user@host:/path 形式）。
    // 注意：在「配置」里把默认值改成 false 后，GitLab/MR、定时、Replay 等触发的构建仍可能沿用「上一次构建」已保存的参数（曾为 true 就会一直是 true），与当前界面默认值无关。
    // 处理：用手动「Build with Parameters」选 false 跑一次以覆盖缓存；或删参数再建；或在 Rebuild 时改参数。
    // 切勿在 environment 里声明 SKIP_FULL_BUILD：声明式 Pipeline 会在每个 stage 重新注入 environment，会覆盖「获取 Commit ID」里设置的 env.SKIP_FULL_BUILD='true'，导致 when 跳过永远不生效。

    triggers {
        gitlab(
            triggerOnMergeRequest: true,
            triggerOnPush: true,
            triggerOnNoteRequest: true,
            noteRegex: '/retry',
            branchFilterType: 'RegexBasedFilter',
            // 模糊匹配：main开头分支
            targetBranchRegex: '^main.*',
            // 与 GitLab Webhook 中填写的 Secret token 保持一致
            secretToken: "change-me-ios-mr-build"
        )
    }

    stages {
        stage('获取 Commit ID') {
            steps {
                script {
                    echo "阶段：获取 Commit ID · WORKSPACE=${env.WORKSPACE} · BUILD_NUMBER=${env.BUILD_NUMBER ?: '?'}"

                    // 1) 先尝试从上次成功构建的归档中拉取基线 last_build_commit，读到 Groovy 局部变量后再清空工作区也无妨
                    //    基线文件名不用隐藏文件，便于 archiveArtifacts 与 artifact URL 稳定匹配
                    def lastBuildCommitFile = "last_build_commit"
                    def lastCommit = ""

                    // HTTP 拉取上次成功构建归档的 last_build_commit（需 agent 能访问 Jenkins；403 需配置读构件权限）
                    if (env.JOB_URL) {
                        def artifactUrl = "${env.JOB_URL}lastSuccessfulBuild/artifact/${lastBuildCommitFile}"
                        withEnv(["CI_LAST_BUILD_ARTIFACT_URL=${artifactUrl}"]) {
                            sh """
                                rm -f ${lastBuildCommitFile}
                                set +e
                                curl -fsSL --connect-timeout 15 --max-time 30 -o ${lastBuildCommitFile} "\$CI_LAST_BUILD_ARTIFACT_URL"
                                if [ \$? -ne 0 ]; then
                                    rm -f ${lastBuildCommitFile}
                                fi
                                exit 0
                            """
                        }
                        if (!fileExists(lastBuildCommitFile)) {
                            echo "未拉取到基线构件（HTTP 404/失败）：尚无带 last_build_commit 的成功归档，或本次为首次完整成功前。完整成功一次后会写入构件，下次即可对比。"
                        }
                    }

                    if (fileExists(lastBuildCommitFile)) {
                        lastCommit = readFile(lastBuildCommitFile).trim()
                    } else if (fileExists(".last_build_commit")) {
                        // 兼容历史工作区仅存在隐藏文件名的场景
                        lastCommit = readFile(".last_build_commit").trim()
                    }
                    if (lastCommit) {
                        echo "用于增量对比的上次成功构建提交: ${lastCommit}"
                    }

                    // 2) MR/手动检出（gitlabMrCheckout 内部会清空工作区，因此基线必须在此之前读到变量）
                    //    手动未填 repo_url 时回退 environment 里的 REPO_URL；无 MR Webhook 时默认检出 main
                    gitlabMrCheckout(
                        defaultBranch: 'main'
                    )
                    echo "检出后（供对照）：MR_URL=${env.MR_URL ?: '(空)'}, MR_SOURCE_BRANCH=${env.MR_SOURCE_BRANCH ?: '(空)'}, MR_TARGET_BRANCH=${env.MR_TARGET_BRANCH ?: '(空)'}, MR_AUTHOR=${env.MR_AUTHOR ?: '(空)'}"

                    // 3) 取合并后的当前 HEAD，用于与基线对比
                    env.CURRENT_HEAD = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
                    echo "当前构建 Commit: ${env.CURRENT_HEAD}"

                    // 4) 解析 is_force_build：脚本不在 parameters {} 中声明，未配置时按「自动」处理（即与上次成功为同一提交则跳过）
                    //    使用 params.containsKey 兼容旧构建参数缓存；值兼容 Boolean / "true"/"false" 字符串
                    def hasIsForceBuild = false
                    try { hasIsForceBuild = (params != null) && params.containsKey('is_force_build') } catch (ignored) { hasIsForceBuild = false }
                    def forceFullBuild = false
                    if (hasIsForceBuild) {
                        def raw = params.is_force_build
                        if (raw instanceof Boolean) {
                            forceFullBuild = raw.booleanValue()
                        } else if (raw != null) {
                            forceFullBuild = "${raw}".trim().toLowerCase() == 'true'
                        }
                    }
                    echo "params.is_force_build=${hasIsForceBuild ? params.is_force_build : '(未配置·按自动模式)'} → forceFullBuild=${forceFullBuild}"

                    // 5) 与上次成功构建指向同一提交且未强制时，跳过后续编译/拷贝/通知阶段
                    if (lastCommit && lastCommit == env.CURRENT_HEAD && !forceFullBuild) {
                        env.SKIP_FULL_BUILD = 'true'
                        echo "分支无新提交（与上次成功构建基线 last_build_commit 一致），后续阶段已跳过，任务将立即结束"
                    } else {
                        env.SKIP_FULL_BUILD = 'false'
                        if (forceFullBuild && lastCommit && lastCommit == env.CURRENT_HEAD) {
                            echo "已选择「强制完整构建」（is_force_build=true）：与上次成功提交相同仍将执行编译与飞书"
                        }
                    }
                    echo "env.SKIP_FULL_BUILD=${env.SKIP_FULL_BUILD}（后续 when 阶段据此跳过编译/拷贝/飞书）"
                }
            }
        }

        stage('读取版本号') {
            when {
                expression { return env.SKIP_FULL_BUILD?.trim() != 'true' }
            }
            steps {
                script {
                    // 路径取自 Jenkins 任务参数 params.version_file_path（在任务「参数化构建过程」里配置）。
                    // getVersion 内部会读首行并校验为非负整数，缺参数或格式不合法时直接 error。
                    def versionCode = getVersion()
                    echo "当前版本号: ${versionCode}"

                    // 取 Jenkins 任务参数 params.version_pre（在任务「参数化构建过程」里配置），支持两种模式：
                    // 1) 完整版本号：X.Y.Z（例：1.0.33）-> 直接使用，不再与 versionCode 拼接；
                    // 2) 版本前缀：X.Y. 或 X.Y（例：1.0. / 1.0）-> 与 versionCode 拼接为 X.Y.Z。
                    def versionPre = params.version_pre?.toString()?.trim()
                    if (!versionPre) {
                        error '缺少参数 version_pre：请在 Jenkins 任务「参数化构建过程」中添加 version_pre（例：1.0.），用于与 versionCode 拼接为 X.Y.Z 后写回 pubspec.yaml。'
                    }
                    // 明确区分完整版本与前缀版本，避免歧义输入（如 1.0.33 + versionCode 29 -> 1.0.33.29）误入拼接逻辑。
                    def isFullSemver = (versionPre ==~ /^\d+\.\d+\.\d+$/)
                    def isVersionPrefix = (versionPre ==~ /^\d+\.\d+\.?$/)
                    if (!isFullSemver && !isVersionPrefix) {
                        error "Jenkins 参数 version_pre 格式不合法：[${versionPre}]。仅支持完整版本号（X.Y.Z，如 1.0.33）或版本前缀（X.Y. / X.Y，如 1.0.）。"
                    }
                    def fullVersion
                    if (isFullSemver) {
                        fullVersion = versionPre
                        echo "version_pre 为完整版本号，直接使用：${fullVersion}"
                    } else {
                        if (!versionPre.endsWith('.')) {
                            versionPre = "${versionPre}."
                        }
                        fullVersion = "${versionPre}${versionCode}"
                        echo "拼接版本号: version_pre=${versionPre} + versionCode=${versionCode} -> ${fullVersion}"
                    }
                    updatePubspecVersion(version: fullVersion)
                }
            }
        }

        stage('编译 IPA') {
            when {
                // 必须用字符串比较；若写成 env.XXX == true 会与字符串 'true' 不相等导致 when 误判
                expression { return env.SKIP_FULL_BUILD?.trim() != 'true' }
            }
            steps {
                echo "阶段：编译 IPA · JOB_NAME=${env.JOB_NAME ?: '(空)'} · BUILD_NUMBER=${env.BUILD_NUMBER ?: '?'} · env.SKIP_FULL_BUILD=${env.SKIP_FULL_BUILD ?: '(空)'}"
                echo "params.env（prod/pre/dev，用于选择 dart-define-from-file 配置文件，非法值回退 config/prod.json）=${params.env != null && params.env.toString().trim() ? params.env : '(未配置)'}"
                echo "params.distribute_type（testFlight=TestFlight plist 导出；app=App Store plist 导出；其它=跳过 xcodebuild -exportArchive）=${params.distribute_type != null && params.distribute_type.toString().trim() ? params.distribute_type : '(未配置)'}"
                script {
                    def exportMethod = (params.export_method != null && params.export_method.toString().trim()) ? params.export_method.toString().trim() : 'development'
                    def distributeType = (params.distribute_type != null) ? params.distribute_type.toString().trim() : ''
                    echo "params.export_method（flutter build ipa --export-method，未填默认 development）=${exportMethod}"
                    echo "[编译 IPA] 传入 Shell：CI_DISTRIBUTE_TYPE=[${distributeType}]"
                    withEnv([
                        "CI_ENV_PROFILE=${params.env != null ? params.env.toString().trim().toLowerCase() : ''}",
                        "CI_DISTRIBUTE_TYPE=${distributeType}",
                        "EXPORT_METHOD=${exportMethod}"
                    ]) {
                    sh '''
                bash << 'EOF'
                    # 任何命令失败都立即退出，避免掩盖首个错误
                    set -euo pipefail

                    echo "[编译 IPA] WORKSPACE=${WORKSPACE}"
                    echo "[编译 IPA] CI_ENV_PROFILE=${CI_ENV_PROFILE}"
                    echo "[编译 IPA] EXPORT_METHOD=${EXPORT_METHOD}"
                    echo "[编译 IPA] CI_DISTRIBUTE_TYPE=${CI_DISTRIBUTE_TYPE:-}"

                    # macOS 构建机上 Xcode / CocoaPods / Ruby 等工具链通常在 ~/.zshrc 里配置，存在时加载一次。
                    if [ -f "${HOME}/.zshrc" ]; then
                        set +u
                        source "${HOME}/.zshrc"
                        set -u
                    fi
                    # 按 environment 的 FLUTTER_SDK_DIR 显式把 flutter 加入 PATH。
                    if [ -n "${FLUTTER_SDK_DIR:-}" ]; then
                        export PATH="${FLUTTER_SDK_DIR}/bin:$PATH"
                    fi
                    echo "[编译 IPA] which flutter=$(command -v flutter || true)"
                    echo "[编译 IPA] 执行命令: flutter clean"
                    flutter clean
                    echo "[编译 IPA] 执行命令: flutter pub get"
                    flutter pub get

                    # 根据 params.env（透传为 CI_ENV_PROFILE）选择 dart-define 配置文件，未配置或非法值时回退到 prod.json
                    case "${CI_ENV_PROFILE}" in
                        pre)  DART_DEFINE_FILE="config/pre.json" ;;
                        dev)  DART_DEFINE_FILE="config/dev.json" ;;
                        prod) DART_DEFINE_FILE="config/prod.json" ;;
                        *)    DART_DEFINE_FILE="config/prod.json" ;;
                    esac
                    echo "[编译 IPA] DART_DEFINE_FILE=${DART_DEFINE_FILE}"
                    echo "[编译 IPA] 执行命令: flutter build ipa --dart-define-from-file=\"${DART_DEFINE_FILE}\" --export-method \"${EXPORT_METHOD}\""
                    flutter build ipa --dart-define-from-file="${DART_DEFINE_FILE}" --export-method "${EXPORT_METHOD}"

                    # distribute_type：testFlight 用 TestFlight plist；app 用 App Store plist；其它值跳过 export
                    DT=$(echo -n "${CI_DISTRIBUTE_TYPE:-}" | tr '[:upper:]' '[:lower:]')
                    if [ "${DT}" = "testflight" ]; then
                        EXPORT_OPTIONS_PLIST="${IOS_EXPORT_OPTIONS_DIR}/testFlight/ExportOptions.plist"
                        echo "[编译 IPA] distribute_type=testFlight，执行 xcodebuild -exportArchive"
                        echo "[编译 IPA] 执行命令: xcodebuild -exportArchive -archivePath build/ios/archive/Runner.xcarchive -exportPath build/ios/ipaFile -exportOptionsPlist ${EXPORT_OPTIONS_PLIST}"
                        xcodebuild -exportArchive \
                            -archivePath build/ios/archive/Runner.xcarchive \
                            -exportPath build/ios/ipaFile \
                            -exportOptionsPlist "${EXPORT_OPTIONS_PLIST}"
                    elif [ "${DT}" = "app" ]; then
                        EXPORT_OPTIONS_PLIST="${IOS_EXPORT_OPTIONS_DIR}/appStore/ExportOptions.plist"
                        echo "[编译 IPA] distribute_type=app，执行 xcodebuild -exportArchive"
                        echo "[编译 IPA] 执行命令: xcodebuild -exportArchive -archivePath build/ios/archive/Runner.xcarchive -exportPath build/ios/ipaFile -exportOptionsPlist ${EXPORT_OPTIONS_PLIST}"
                        xcodebuild -exportArchive \
                            -archivePath build/ios/archive/Runner.xcarchive \
                            -exportPath build/ios/ipaFile \
                            -exportOptionsPlist "${EXPORT_OPTIONS_PLIST}"
                    else
                        echo "[编译 IPA] distribute_type=${CI_DISTRIBUTE_TYPE:-(未配置)}，跳过 xcodebuild -exportArchive"
                    fi

EOF
                '''
                    }
                }
            }
        }

        stage('拷贝IPA到远程服务器') {
            when {
                expression { return env.SKIP_FULL_BUILD?.trim() != 'true' }
            }
            steps {
                script {
                    echo "阶段：拷贝 IPA 到远程服务器 · 远程主机 ${env.ARTIFACT_HOST}"
                    // IPA 路径优先取 Jenkins 参数 params.ipaFile，未配置或为空时回退到默认查找逻辑（build/ios/ipa/ 下最新的 *.ipa）
                    def defaultIpaFile = sh(
                        script: "ls -t build/ios/ipa/*.ipa 2>/dev/null | head -1",
                        returnStdout: true
                    ).trim()
                    def paramIpaFile = (params.ipaFile != null) ? params.ipaFile.toString().trim() : ''
                    def ipaFile = paramIpaFile ? paramIpaFile : defaultIpaFile
                    echo "params.ipaFile=${paramIpaFile ?: '(未配置，使用默认值)'} · 默认值=${defaultIpaFile ?: '(未找到)'}"

                    if (!ipaFile) {
                        error "未找到 IPA 文件，无法拷贝到远程服务器"
                    }
                    echo "本地 IPA 路径: ${ipaFile}"

                    // 生成时间戳：mm-dd_hh-mm-ss（文件名）和 yyyy-mm-dd（目录名）
                    def dateForDir = sh(script: "date +%Y-%m-%d", returnStdout: true).trim()
                    def dateForFile = sh(script: "date +%m-%d_%H-%M-%S", returnStdout: true).trim()
                    def newIpaName = "${dateForFile}_mr.ipa"
                    def remoteDir = "${env.ARTIFACT_ROOT_DIR}/mrBuild/${dateForDir}/ios/release"
                    def remotePath = "${remoteDir}/${newIpaName}"

                    echo "拷贝 IPA 到: ${remotePath}"
                    // 保存远程存储路径（从 mrBuild 开始），供飞书通知使用
                    env.REMOTE_IPA_PATH = "mrBuild/${dateForDir}/ios/${params.env}/${newIpaName}"
                    // 统一通过共享步骤拷贝（内部自动判断本机/远程、建目录、5 分钟超时；目标主机取 environment 的 ARTIFACT_HOST）。
                    copyArtifactToRemote(sourcePath: ipaFile, targetPath: remotePath)
                    echo "IPA 已成功拷贝到远程服务器"
                }
            }
        }

        stage('发送飞书通知') {
            when {
                expression { return env.SKIP_FULL_BUILD?.trim() != 'true' }
            }
            steps {
                script {
                    // 与 when 双保险：避免个别 Jenkins 版本下 when 与 env 不同步时仍执行发送
                    if (env.SKIP_FULL_BUILD?.trim() == 'true') {
                        echo '已跳过飞书通知（无新提交）'
                        return
                    }
                    echo "阶段：发送飞书通知（成功） · REMOTE_IPA_PATH=${env.REMOTE_IPA_PATH ?: '(尚未设置)'}"
                    def mrInfo = env.MR_URL ? "**MR 地址：** [${env.MR_URL}](${env.MR_URL})" : ""
                    def authorInfo = env.MR_AUTHOR ? "**提交者：** ${env.MR_AUTHOR}" : ""
                    def branchInfo = (env.MR_SOURCE_BRANCH || env.MR_TARGET_BRANCH) ? "**分支：** ${env.MR_SOURCE_BRANCH ?: '-'} → ${env.MR_TARGET_BRANCH ?: '-'}" : ""
                    def commitLog = env.COMMIT_MESSAGES ? "**提交记录：**\n${env.COMMIT_MESSAGES}" : ""
                    def remotePathInfo = env.REMOTE_IPA_PATH ? "**远程存储路径（Release）：** ${env.REMOTE_IPA_PATH}" : ""
                    def content = [mrInfo, authorInfo, branchInfo, commitLog, remotePathInfo].findAll { it }.join('\n\n')

                    // distribute_type=app（应用商店发布场景）时使用「应用商店」专属标题+黄色头部，
                    // 与日常 MR 构建（绿色 📦）区分开。
                    def isAppStoreNotify = (params.distribute_type != null) && params.distribute_type.toString().trim().toLowerCase() == 'app'
                    def cardTitle = isAppStoreNotify ? "应用商店IPA构建完成" : "📦 iOS 构建完成"
                    def cardTemplate = isAppStoreNotify ? "yellow" : "green"

                    sendFeishuCard(card: [
                        header: [
                            title: [
                                tag: "plain_text",
                                content: cardTitle
                            ],
                            template: cardTemplate
                        ],
                        elements: [
                            [
                                tag: "markdown",
                                content: content
                            ],
                            [
                                tag: "action",
                                actions: [
                                    [
                                        tag: "button",
                                        text: [
                                            tag: "plain_text",
                                            content: "查看构建日志"
                                        ],
                                        url: env.BUILD_URL ?: '',
                                        type: "primary"
                                    ]
                                ]
                            ]
                        ]
                    ])
                }
            }
        }
    }

    // 构建失败时发送飞书通知；成功时写入 last_build_commit 供下次增量对比
    post {
        always {
            script {
                echo "post always: SKIP_FULL_BUILD=${env.SKIP_FULL_BUILD ?: '(空)'}"
                if (env.SKIP_FULL_BUILD?.trim() == 'true') {
                    echo "post always: 将构建结果标为 NOT_BUILT（无新提交，本次未执行完整构建）"
                    currentBuild.result = 'NOT_BUILT'
                    currentBuild.description = '无新提交，已跳过'
                }
            }
        }
        success {
            script {
                if (env.SKIP_FULL_BUILD?.trim() != 'true') {
                    // 必须写「检出时」捕获的 env.CURRENT_HEAD，而非当下的 git rev-parse HEAD：
                    // 若后续在编译阶段引入 git apply / git merge FETCH_HEAD 等会改动本地历史的操作，
                    // 运行结束时的 HEAD 会偏离远端分支真实提交，导致下次增量对比永远未命中。
                    def baselineCommit = env.CURRENT_HEAD?.trim()
                    if (!baselineCommit) {
                        error 'post success: env.CURRENT_HEAD 为空，无法写入 last_build_commit 基线（请检查「检出代码」阶段是否正常执行）'
                    }
                    echo "post success: 写入 last_build_commit=${baselineCommit} 并 archiveArtifacts（供下次增量对比）"
                    writeFile file: 'last_build_commit', text: "${baselineCommit}\n"
                    // 供后续构建通过 HTTP 拉取，与「仅写工作区文件」组合才能跨节点跳过无新提交
                    archiveArtifacts artifacts: 'last_build_commit', fingerprint: true
                }
            }
        }
        failure {
            script {
                // 跳过模式下不应触发失败分支；若个别异常仍走到这里，避免误发飞书
                if (env.SKIP_FULL_BUILD?.trim() == 'true') {
                    echo "post failure: SKIP_FULL_BUILD=true，跳过失败飞书通知"
                    return
                }
                echo "阶段：post failure · 发送飞书失败通知 · BUILD_URL=${env.BUILD_URL ?: '(空)'}"
                def mrInfo = env.MR_URL ? "**MR 地址：** [${env.MR_URL}](${env.MR_URL})" : ""
                def authorInfo = env.MR_AUTHOR ? "**提交者：** ${env.MR_AUTHOR}" : ""
                def branchInfo = (env.MR_SOURCE_BRANCH || env.MR_TARGET_BRANCH) ? "**分支：** ${env.MR_SOURCE_BRANCH ?: '-'} → ${env.MR_TARGET_BRANCH ?: '-'}" : ""
                def commitLog = env.COMMIT_MESSAGES ? "**提交记录：**\n${env.COMMIT_MESSAGES}" : ""
                def content = [mrInfo, authorInfo, branchInfo, commitLog].findAll { it }.join('\n\n')
                if (!content) content = "构建失败，请查看 Jenkins 日志获取详情。"

                // 与成功通知一致：distribute_type=app 时按应用商店场景展示标题
                def isAppStoreNotify = (params.distribute_type != null) && params.distribute_type.toString().trim().toLowerCase() == 'app'
                def cardTitle = isAppStoreNotify ? "❌ 应用商店IPA构建失败" : "❌ iOS 构建失败"

                sendFeishuCard(card: [
                    header: [
                        title: [
                            tag: "plain_text",
                            content: cardTitle
                        ],
                        template: "red"
                    ],
                    elements: [
                        [
                            tag: "markdown",
                            content: content
                        ],
                        [
                            tag: "action",
                            actions: [
                                [
                                    tag: "button",
                                    text: [
                                        tag: "plain_text",
                                        content: "查看构建日志"
                                    ],
                                    url: env.BUILD_URL ?: '',
                                    type: "primary"
                                ]
                            ]
                        ]
                    ]
                ])
            }
        }
    }
}
