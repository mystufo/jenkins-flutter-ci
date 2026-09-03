@Library('jenkins-flutter-ci') _

// 本文件由仓库根目录对应入口 mr_build_android.groovy 通过 evaluate 加载。
// Jenkins Job 的 Script Path 请保持指向根目录入口，以便脚本编译失败时仍能发飞书。

pipeline {
    agent any

    // ============================ 项目配置（按需修改） ============================
    // 与部署环境相关的常量集中在这里。凭据类信息（Git SSH Key、蒲公英 API Key、飞书 Webhook）
    // 不放在源码里，统一走 Jenkins 凭据，这里只填凭据 ID。
    // 注意：Declarative 的 triggers {} 块无法引用 environment，GitLab secretToken 请直接改下方 triggers。
    environment {
        // 默认检出的仓库地址（GitLab Webhook 触发时以 Webhook 注入的地址为准；手动触发可用 repo_url 参数覆盖）
        REPO_URL = 'git@gitlab.example.com:group/your-flutter-app.git'
        // 拉取代码用的凭据 ID（类型：SSH Username with private key）；构建机已免密 ssh 到 GitLab 时设为 ''
        GIT_CREDENTIALS_ID = 'gitlab-ssh-key'
        // 飞书机器人 Webhook 的凭据 ID（类型：Secret text，值为完整 webhook URL）
        FEISHU_WEBHOOK_CREDENTIALS_ID = 'feishu-webhook'
        // 蒲公英 API Key 的凭据 ID（类型：Secret text）
        PGYER_CREDENTIALS_ID = 'pgyer-api-key'
        // 产物服务器（user@host）与产物根目录；构建机本身就是产物服务器时会自动改用本地 cp
        ARTIFACT_HOST = 'root@192.168.1.100'
        ARTIFACT_ROOT_DIR = '/data/build/your-flutter-app'
        // Flutter SDK 目录（为空则直接使用 PATH 里的 flutter）
        FLUTTER_SDK_DIR = '/opt/flutter'
        // Android 签名配置 key.properties 的绝对路径，编译前拷贝到工程 android/ 目录（为空则不拷贝）
        ANDROID_KEY_PROPERTIES = '/opt/ci/android/key.properties'
        // GitLab Webhook 注入的仓库主页若是内网地址，生成 MR 链接时把前者替换为后者（任一为空则不替换）
        GITLAB_INTERNAL_URL = ''
        GITLAB_PUBLIC_URL = ''
    }

    // 以下参数请在 Jenkins 任务「参数化构建过程」里手动添加（勿在脚本里声明 parameters 默认值）：
    // BRANCH：无 GitLab Webhook 时作为 gitlabMrCheckout 的检出分支；未配置时脚本内回退 main。
    // env（字符串，prod / pre / dev，用于选择 flutter build apk --dart-define-from-file 的配置文件，未配置或非法值回退 config/prod.json）、
    // is_force_build（true=强制完整构建，未配置或 false=按自动模式：与上次成功构建为同一提交则跳过）、
    // version_pre（支持两种模式：1) 完整版本号 X.Y.Z 如 1.0.33 直接使用；2) 版本前缀 X.Y. / X.Y 与 getVersion() 读出的 versionCode 拼接为 X.Y.Z 后写回 pubspec.yaml）、
    // version_file_path（版本号文件路径，支持本地路径或 user@host:/path 形式）、
    // official_version（true 时除 mrBuild 日期目录外另拷贝一份 APK 到正式构建目录，并使用「应用商店」样式的飞书标题）。
    // 注意：在「配置」里把默认值改成 false 后，GitLab/MR、定时、Replay 等触发的构建仍可能沿用「上一次构建」已保存的参数（曾为 true 就会一直是 true），与当前界面默认值无关。
    // 处理：用手动「Build with Parameters」选 false 跑一次以覆盖缓存；或删参数再建；或在 Rebuild 时改参数。
    // 切勿在 environment 里声明 SKIP_FULL_BUILD：声明式 Pipeline 会在每个 stage 重新注入 environment，会覆盖「获取 Commit ID」里设置的 env.SKIP_FULL_BUILD='true'，导致 when 跳过永远不生效。

    triggers {
        gitlab(
            triggerOnMergeRequest: true,
            // Push 到匹配分支时也构建（合并进目标分支 / 直接 push）。
            // GitLab 仓库 Webhook 须勾选 Push events；改完后建议手动跑一次任务以同步 Job 触发器。
            triggerOnPush: true,
            triggerOnNoteRequest: true,
            noteRegex: '/retry',
            branchFilterType: 'RegexBasedFilter',
            // 模糊匹配：main 开头分支（MR 的目标分支，或 Push 的分支名）
            targetBranchRegex: '^release/v.*$',
            // 与 GitLab Webhook 中填写的 Secret token 保持一致
            secretToken: "change-me-android-mr-build"
        )
    }

    stages {
        stage('获取 Commit ID') {
            steps {
                script {
                    echo "阶段：获取 Commit ID · WORKSPACE=${env.WORKSPACE} · BUILD_NUMBER=${env.BUILD_NUMBER ?: '?'}"
                    echo "params.BRANCH（无 MR Webhook 时检出分支）=${params.BRANCH != null && params.BRANCH.toString().trim() ? params.BRANCH : '(未配置，用 main)'}"

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

                    // 2) MR / Push / 手动检出（gitlabMrCheckout 内部会清空工作区，因此基线必须在此之前读到变量）
                    //    无 Webhook 时用 params.BRANCH；手动未填 repo_url 时回退 environment 里的 REPO_URL
                    gitlabMrCheckout(
                        defaultBranch: params.BRANCH?.toString()?.trim() ?: 'main'
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
                    // 保存最终版本号，供 APK 文件名、蒲公英说明与飞书通知复用。
                    env.APP_VERSION = fullVersion
                }
            }
        }

        stage('编译 APK') {
            when {
                // 必须用字符串比较；若写成 env.XXX == true 会与字符串 'true' 不相等导致 when 误判
                expression { return env.SKIP_FULL_BUILD?.trim() != 'true' }
            }
            steps {
                echo "阶段：编译 APK · JOB_NAME=${env.JOB_NAME ?: '(空)'} · BUILD_NUMBER=${env.BUILD_NUMBER ?: '?'} · env.SKIP_FULL_BUILD=${env.SKIP_FULL_BUILD ?: '(空)'}"
                echo "params.env（prod/pre/dev，用于选择 dart-define-from-file 配置文件，非法值回退 config/prod.json）=${params.env != null && params.env.toString().trim() ? params.env : '(未配置)'}"
                script {
                    withEnv([
                        "CI_ENV_PROFILE=${params.env != null ? params.env.toString().trim().toLowerCase() : ''}"
                    ]) {
                    sh '''
                bash << 'EOF'
                    # 任何命令失败都立即退出，避免掩盖首个错误
                    set -euo pipefail

                    echo "[编译 APK] WORKSPACE=${WORKSPACE}"
                    echo "[编译 APK] CI_ENV_PROFILE=${CI_ENV_PROFILE}"

                    # 非交互式 shell 下 ~/.bashrc 往往不会生效，这里按 environment 的 FLUTTER_SDK_DIR 显式把 flutter 加入 PATH。
                    if [ -n "${FLUTTER_SDK_DIR:-}" ]; then
                        export PATH="${FLUTTER_SDK_DIR}/bin:$PATH"
                    fi
                    echo "[编译 APK] which flutter=$(command -v flutter || true)"
                    echo "[编译 APK] 执行命令: flutter clean"
                    flutter clean
                    echo "[编译 APK] 执行命令: flutter pub get"
                    flutter pub get

                    # 根据 params.env（透传为 CI_ENV_PROFILE）选择 dart-define 配置文件，未配置或非法值时回退到 prod.json
                    case "${CI_ENV_PROFILE}" in
                        pre)  DART_DEFINE_FILE="config/pre.json" ;;
                        dev)  DART_DEFINE_FILE="config/dev.json" ;;
                        prod) DART_DEFINE_FILE="config/prod.json" ;;
                        *)    DART_DEFINE_FILE="config/prod.json" ;;
                    esac
                    echo "[编译 APK] DART_DEFINE_FILE=${DART_DEFINE_FILE}"

                    # 编译前把 Android 签名配置 key.properties 拷贝到工程 android/ 目录（environment 未配置时跳过）。
                    if [ -n "${ANDROID_KEY_PROPERTIES:-}" ]; then
                        echo "[编译 APK] 执行命令: cp ${ANDROID_KEY_PROPERTIES} android/"
                        cp "${ANDROID_KEY_PROPERTIES}" android/
                    else
                        echo "[编译 APK] 未配置 ANDROID_KEY_PROPERTIES，使用工程自带的签名配置"
                    fi
                    echo "[编译 APK] 执行命令: flutter build apk --target-platform=android-arm64 --dart-define-from-file=\"${DART_DEFINE_FILE}\""
                    flutter build apk --target-platform=android-arm64 --dart-define-from-file="${DART_DEFINE_FILE}"
                    echo "[编译 APK] 完成"
EOF
                '''
                    }
                }
            }
        }

        stage('拷贝 APK 到远程服务器') {
            when {
                expression { return env.SKIP_FULL_BUILD?.trim() != 'true' }
            }
            steps {
                script {
                    echo "阶段：拷贝 APK 到远程服务器 · 目标 ${env.ARTIFACT_HOST}（统一 cp/scp，单条拷贝超 5 分钟即失败退出）"
                    // flutter build apk 默认产物路径：build/app/outputs/flutter-apk/app-release.apk
                    def apkFile = sh(
                        script: "ls -t build/app/outputs/flutter-apk/*.apk 2>/dev/null | head -1",
                        returnStdout: true
                    ).trim()

                    if (!apkFile) {
                        error "未找到 APK 文件，无法拷贝到远程服务器"
                    }
                    echo "本地 APK 路径: ${apkFile}"

                    // 生成时间戳：mm-dd_hh-mm-ss（文件名）和 yyyy-mm-dd（目录名）
                    def dateForDir = sh(script: "date +%Y-%m-%d", returnStdout: true).trim()
                    def dateForFile = sh(script: "date +%m-%d_%H-%M-%S", returnStdout: true).trim()
                    // 文件名末尾附带应用版本号（与 updatePubspecVersion 写入的 env.APP_VERSION 一致）。
                    def verForFile = (env.APP_VERSION ?: '').trim()
                    if (!verForFile) {
                        verForFile = 'unknown'
                    }
                    verForFile = verForFile.replaceAll(/[^\w.-]/, '_')
                    def newApkName = "${dateForFile}_mr_${verForFile}.apk"
                    def remoteDir = "${env.ARTIFACT_ROOT_DIR}/mrBuild/${dateForDir}/android"
                    def remotePath = "${remoteDir}/${newApkName}"

                    // 统一通过共享步骤拷贝（内部自动判断本机/远程、建目录、5 分钟超时；目标主机取 environment 的 ARTIFACT_HOST）。
                    copyArtifactToRemote(sourcePath: apkFile, targetPath: remotePath)

                    // official_version=true 时额外同步至正式构建目录（兼容 Boolean / 字符串 "true"）。
                    def isOfficialVersion = false
                    if (params.official_version != null) {
                        if (params.official_version instanceof Boolean) {
                            isOfficialVersion = params.official_version.booleanValue()
                        } else {
                            isOfficialVersion = params.official_version.toString().trim().toLowerCase() == 'true'
                        }
                    }
                    if (isOfficialVersion) {
                        def officialDir = "${env.ARTIFACT_ROOT_DIR}/appBuild/android"
                        def officialPath = "${officialDir}/${newApkName}"
                        echo "params.official_version=true：额外拷贝 APK 到 ${officialPath}"
                        copyArtifactToRemote(sourcePath: apkFile, targetPath: officialPath)
                    }

                    // 保存远程存储路径（从 mrBuild 开始），供飞书通知使用
                    env.REMOTE_APK_PATH = "mrBuild/${dateForDir}/android/${newApkName}"
                    echo "APK 已成功拷贝到远程服务器"
                }
            }
        }

        stage('上传蒲公英') {
            when {
                expression { return env.SKIP_FULL_BUILD?.trim() != 'true' }
            }
            steps {
                script {
                    echo "阶段：上传蒲公英（失败不阻断流水线，错误写入 PGYER_UPLOAD_ERROR）"
                    // 上传实现见共享库 vars/pgyerUpload.groovy：走蒲公英「快速上传」三步式，且内部吞掉所有异常，
                    // 只把失败说明放进 error 字段，保证蒲公英故障不阻断流水线，由飞书通知与 MR Comment 展示。
                    def pgyer = pgyerUpload(
                        apkGlob   : 'build/app/outputs/flutter-apk/*.apk',
                        appVersion: env.APP_VERSION
                    )
                    env.PGYER_URL = pgyer.url
                    env.PGYER_QRCODE_URL = pgyer.qrCodeUrl
                    env.PGYER_BUILD_KEY = pgyer.buildKey
                    env.PGYER_VERSION_URL = pgyer.versionUrl
                    env.PGYER_UPLOAD_ERROR = pgyer.error
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
                    echo "阶段：发送飞书通知（成功） · REMOTE_APK_PATH=${env.REMOTE_APK_PATH ?: '(尚未设置)'} · PGYER_URL=${env.PGYER_URL ?: '(尚未上传)'} · PGYER_UPLOAD_ERROR=${env.PGYER_UPLOAD_ERROR ?: '(无)'}"
                    def mrInfo = env.MR_URL ? "**MR 地址：** [${env.MR_URL}](${env.MR_URL})" : ""
                    def authorInfo = env.MR_AUTHOR ? "**提交者：** ${env.MR_AUTHOR}" : ""
                    def branchInfo = (env.MR_SOURCE_BRANCH || env.MR_TARGET_BRANCH) ? "**分支：** ${env.MR_SOURCE_BRANCH ?: '-'} → ${env.MR_TARGET_BRANCH ?: '-'}" : ""
                    def commitLog = env.COMMIT_MESSAGES ? "**提交记录：**\n${env.COMMIT_MESSAGES}" : ""
                    def versionInfo = env.APP_VERSION?.trim() ? "**版本号：** ${env.APP_VERSION}" : ""
                    def downloadInfo = env.PGYER_URL ? "**下载地址：** [${env.PGYER_URL}](${env.PGYER_URL})" : "**下载地址：** 蒲公英未上传成功"
                    def qrcodeInfo = env.PGYER_QRCODE_URL ? "**二维码：** [扫码安装](${env.PGYER_QRCODE_URL})" : ""
                    def versionUrlInfo = env.PGYER_VERSION_URL ? "**本次版本固定地址：** [${env.PGYER_VERSION_URL}](${env.PGYER_VERSION_URL})" : ""
                    def buildKeyInfo = env.PGYER_BUILD_KEY ? "**蒲公英构建标识：** ${env.PGYER_BUILD_KEY}" : ""
                    def pgyerErrorInfo = env.PGYER_UPLOAD_ERROR ? "**蒲公英说明：** ${env.PGYER_UPLOAD_ERROR}" : ""
                    def remotePathInfo = env.REMOTE_APK_PATH ? "**远程存储路径：** ${env.REMOTE_APK_PATH}" : ""
                    def content = [mrInfo, authorInfo, branchInfo, commitLog, versionInfo, downloadInfo, qrcodeInfo, versionUrlInfo, buildKeyInfo, pgyerErrorInfo, remotePathInfo].findAll { it }.join('\n\n')

                    // official_version=true 时使用「应用商店」专属标题+黄色头部，与日常 MR 构建（绿色 📦）区分开。
                    def isOfficialNotify = false
                    if (params.official_version != null) {
                        if (params.official_version instanceof Boolean) {
                            isOfficialNotify = params.official_version.booleanValue()
                        } else {
                            isOfficialNotify = params.official_version.toString().trim().toLowerCase() == 'true'
                        }
                    }
                    def cardTitle = isOfficialNotify ? "应用商店 APK 构建完成" : "📦 APK 构建完成"
                    def cardTemplate = isOfficialNotify ? "yellow" : "green"

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
                                            content: env.PGYER_URL ? "下载 APK" : "蒲公英未就绪（看日志）"
                                        ],
                                        url: env.PGYER_URL ?: (env.BUILD_URL ?: ''),
                                        type: env.PGYER_URL ? "primary" : "default"
                                    ],
                                    [
                                        tag: "button",
                                        text: [
                                            tag: "plain_text",
                                            content: "查看构建日志"
                                        ],
                                        url: env.BUILD_URL ?: '',
                                        type: "default"
                                    ]
                                ]
                            ]
                        ]
                    ])
                }
            }
        }

        // 在 GitLab MR 上追加一条 comment，把产物的服务器存储地址回写到讨论区。
        // 仅在 MR Webhook 触发（gitlabMergeRequestIid 非空）时执行；Push / 手动 BRANCH 触发会跳过。
        stage('回写 GitLab MR Comment') {
            when {
                expression {
                    return env.SKIP_FULL_BUILD?.trim() != 'true' &&
                        (env.gitlabMergeRequestIid ?: '').toString().trim() &&
                        env.gitlabMergeRequestIid != 'null'
                }
            }
            steps {
                script {
                    echo "阶段：回写 GitLab MR Comment · iid=${env.gitlabMergeRequestIid} · REMOTE_APK_PATH=${env.REMOTE_APK_PATH ?: '(空)'} · PGYER_VERSION_URL=${env.PGYER_VERSION_URL ?: '(空)'}"
                    postGitLabMRComment(
                        platform     : 'Android',
                        status       : 'success',
                        artifactPath : env.REMOTE_APK_PATH,
                        // 回写本次版本固定地址（buildKey 绑定），避免短链始终指向最新版。
                        // 注意：该地址含蒲公英 _api_key，MR 评论对所有能看到 MR 的人可见；不希望暴露时改为 env.PGYER_URL。
                        pgyerUrl     : env.PGYER_VERSION_URL ?: env.PGYER_URL,
                        pgyerError   : env.PGYER_UPLOAD_ERROR
                    )
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
                def versionInfo = env.APP_VERSION?.trim() ? "**版本号：** ${env.APP_VERSION}" : ""
                def content = [mrInfo, authorInfo, branchInfo, commitLog, versionInfo].findAll { it }.join('\n\n')
                if (!content) content = "构建失败，请查看 Jenkins 日志获取详情。"

                sendFeishuCard(card: [
                    header: [
                        title: [
                            tag: "plain_text",
                            content: "❌ APK 构建失败"
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

                // 失败时也尝试回写 GitLab MR Comment，便于 reviewer 直接拿到失败链接。
                // 此处可能在「拷贝 APK 到远程服务器」阶段之前就已失败，REMOTE_APK_PATH 可能为空。
                if ((env.gitlabMergeRequestIid ?: '').toString().trim() && env.gitlabMergeRequestIid != 'null') {
                    postGitLabMRComment(
                        platform    : 'Android',
                        status      : 'failure',
                        artifactPath: env.REMOTE_APK_PATH,
                        pgyerError  : env.PGYER_UPLOAD_ERROR
                    )
                }
            }
        }
    }
}
