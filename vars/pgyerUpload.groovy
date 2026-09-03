/**
 * 把编译产物上传到蒲公英，并等待发布完成，返回下载短链 / 二维码 / buildKey 等信息。
 *
 * 走蒲公英「快速上传」三步式，而非旧版 /apiv2/app/upload 直传：
 *   1. POST /apiv2/app/getCOSToken 取预上传凭证（请求体只有几 KB，更新说明在这一步提交）；
 *   2. 把产物直传到返回的腾讯云 COS endpoint，成功返回 204 No Content；
 *   3. 轮询 GET /apiv2/app/buildInfo 确认异步发布完成，再取短链 / 二维码 / buildKey。
 * 旧接口要把整个安装包推过蒲公英站点网关，大包耗时一旦超过网关上游超时就返回 522（Cloudflare 源站超时），
 * 而 curl -f 遇到该状态只留下一个 exit code 22，既拿不到响应体也无法区分故障类型，故弃用。
 *
 * 关于接口域名：走 api.pgyer.com 而非站点域名 www.pgyer.com。后者是浏览器入口，挂在 Cloudflare
 * 之后，API 请求经它转发会额外受站点网关的上游超时约束，正是 522 的来源之一。
 *
 * 关于表单字段：除 file 外全部用 curl --form-string 而非 -F。-F 会解析值里的分号，把其后的内容
 * 当成 ;type= / ;filename= 之类的字段参数，而 COS 签名串形如 q-sign-time=1785000000;1785007200，
 * 用 -F 提交会在第一个分号处被截断，签名里的时效信息随之丢失，COS 会回 403 AccessDenied /
 * Request has expired —— 看着像凭证过期，实际是字段被 curl 截断。--form-string 按字面值提交，不做解析。
 *
 * 关于代理：几条 curl 默认都带 --noproxy '*'。构建机常把 HTTP(S)_PROXY 指向本地代理
 * （只为访问境外服务），而蒲公英与腾讯云 COS 均为境内服务，让上百 MB 的安装包
 * 经本地代理转发会显著拉长上游耗时，同样会触发网关 522。若构建机必须经代理才能出网，传 noProxy: false。
 *
 * 失败不抛异常：任何环节出错都只把说明写入返回值的 error 字段，由调用方决定如何展示，
 * 从而保证蒲公英故障不阻断流水线主流程。
 *
 * 用法：
 *   def pgyer = pgyerUpload(apkGlob: 'build/app/outputs/flutter-apk/*.apk')   // Flutter Android
 *   def pgyer = pgyerUpload(filePath: 'build/ios/ipa/app.ipa', buildType: 'ios')
 *   env.PGYER_URL           = pgyer.url
 *   env.PGYER_QRCODE_URL    = pgyer.qrCodeUrl
 *   env.PGYER_BUILD_KEY     = pgyer.buildKey
 *   env.PGYER_VERSION_URL   = pgyer.versionUrl
 *   env.PGYER_UPLOAD_ERROR  = pgyer.error
 *
 * 入参（全部选填）：
 *   apkGlob               定位产物的 shell glob，取修改时间最新的一个，默认 'build*.apk'
 *   filePath              直接指定产物路径；给定时不再按 apkGlob 查找
 *   buildType             蒲公英应用类型，android / ios / harmonyos，默认 'android'
 *   credentialsId         保存蒲公英 API Key 的 Secret text 凭据 ID，默认取流水线 environment 的 PGYER_CREDENTIALS_ID，其次 'pgyer-api-key'
 *   appVersion            更新说明里的版本号，默认取 env.APP_VERSION
 *   updateDescription     完整覆盖更新说明；不传时按「提交人 / 版本号 / 提交记录」自动拼装
 *   maxAttempts           整体重试次数（含首次），默认 3
 *   uploadTimeoutSeconds  单次 COS 上传的 curl 超时，默认 1800
 *   publishTimeoutSeconds 等待蒲公英完成发布的总时长，默认 120（每 5 秒轮询一次）
 *   noProxy               curl 是否绕过 HTTP(S)_PROXY 直连，默认 true
 *
 * 返回 Map：
 *   url         应用短链（始终指向该应用的最新版本），失败时为空串
 *   qrCodeUrl   二维码图片地址，失败时为空串
 *   buildKey    本次上传版本的唯一标识，可用于定位该历史版本
 *   versionUrl  与本次上传绑定的固定下载地址（安装接口 + buildKey），始终指向本次版本。
 *               注意：该 URL 含 _api_key，属敏感信息，分享给他人即等于暴露密钥
 *   error       失败说明；成功时为空串
 */
def call(Map config = [:]) {
    String apkGlob = (config.apkGlob ?: 'build*.apk').toString().trim()
    String filePath = (config.filePath ?: '').toString().trim()
    String buildType = (config.buildType ?: 'android').toString().trim()
    String credentialsId = (config.credentialsId ?: env.PGYER_CREDENTIALS_ID ?: 'pgyer-api-key').toString().trim()
    String appVersion = (config.appVersion ?: env.APP_VERSION ?: '').toString().trim()
    int maxAttempts = (config.maxAttempts ?: 3) as int
    int uploadTimeoutSeconds = (config.uploadTimeoutSeconds ?: 1800) as int
    int publishTimeoutSeconds = (config.publishTimeoutSeconds ?: 120) as int
    boolean noProxy = (config.noProxy == null) ? true : (config.noProxy as boolean)

    Map result = [url: '', qrCodeUrl: '', buildKey: '', versionUrl: '', error: '']

    // 产物定位：显式传入的 filePath 优先，否则按 glob 取修改时间最新的一个。
    String artifact = filePath
    if (!artifact) {
        artifact = sh(script: "ls -t ${apkGlob} 2>/dev/null | head -1", returnStdout: true).trim()
    }
    if (!artifact) {
        result.error = "未找到安装包（${filePath ?: apkGlob}），跳过蒲公英上传"
        echo "[pgyerUpload] ${result.error}"
        return result
    }
    echo "[pgyerUpload] 待上传安装包: ${artifact}"
    // 打印体积：COS 上传失败时可与日志里的耗时、速度一起判断是否为超时。
    sh "ls -lh '${artifact}'"

    String updateDescription = (config.updateDescription ?: '').toString()
    if (!updateDescription) {
        // 与飞书通知 / MR Comment 同源：MR_AUTHOR、版本号、COMMIT_MESSAGES。
        String author = (env.MR_AUTHOR ?: '').trim() ?: '未知'
        updateDescription = "提交人：${author}\n版本号：${appVersion ?: '未知'}"
        String commitLog = (env.COMMIT_MESSAGES ?: '').trim()
        if (commitLog) {
            updateDescription += "\n提交记录：\n${commitLog}"
        }
    }
    echo "[pgyerUpload] 更新说明: ${updateDescription.replace('\n', ' / ')}"

    // 每 5 秒轮询一次发布状态，换算出总轮询次数（至少 1 次）。
    int pollIntervalSeconds = 5
    int maxPolls = Math.max(1, (int) Math.ceil(publishTimeoutSeconds / (double) pollIntervalSeconds))
    String noProxyOpt = noProxy ? "--noproxy '*'" : ''

    // uploaded / giveUp 用标志位而非在闭包里 return：withEnv / withCredentials 的 body 是闭包，
    // 闭包内的 return 只会退出闭包，拿不到 call 方法的返回值。
    // giveUp 表示「已确认重传无意义」：COS 以 4xx 拒绝（签名或权限问题）、蒲公英侧发布失败，
    // 或文件已传完只是发布未确认，这几种情况再传一遍同样的包只是浪费带宽。
    boolean uploaded = false
    boolean giveUp = false
    withEnv(["PGYER_BUILD_DESC=${updateDescription}", "PGYER_ARTIFACT=${artifact}"]) {
        withCredentials([string(credentialsId: credentialsId, variable: 'PGYER_API_KEY')]) {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    echo "[pgyerUpload] 上传尝试 第 ${attempt}/${maxAttempts} 次"
                    Map cosToken = fetchCosToken(noProxyOpt, buildType)
                    Map upload = uploadToCos(noProxyOpt, cosToken, uploadTimeoutSeconds)
                    if (!upload.ok) {
                        giveUp = !upload.retry
                        error "COS 上传失败 HTTP ${upload.httpCode}，统计=${upload.stat}，响应片段: ${upload.body}"
                    }
                    Map published = waitForPublish(noProxyOpt, cosToken.key, maxPolls, pollIntervalSeconds)

                    result.buildKey = published.buildKey ?: cosToken.key
                    if (published.failed) {
                        giveUp = true
                        error "蒲公英发布失败（buildKey=${result.buildKey}）: ${published.lastInfo}"
                    }
                    if (!published.done) {
                        // 包已在 COS 上，只是发布没在预期时间内确认；留下 buildKey 供人工到蒲公英后台核对。
                        giveUp = true
                        error "安装包已上传（${upload.stat}），但 ${publishTimeoutSeconds} 秒内未确认发布完成（${published.lastInfo}），buildKey=${result.buildKey}"
                    }

                    result.url = "https://www.pgyer.com/${published.buildShortcutUrl}"
                    result.qrCodeUrl = published.buildQRCodeURL
                    if (result.buildKey) {
                        result.versionUrl = "https://www.pgyer.com/apiv2/app/install?_api_key=${env.PGYER_API_KEY}&buildKey=${result.buildKey}"
                    }
                    result.error = ''
                    uploaded = true
                    echo "[pgyerUpload] 上传成功，下载地址: ${result.url}，buildKey: ${result.buildKey}"
                    break
                } catch (Exception e) {
                    result.error = "蒲公英上传失败（第 ${attempt}/${maxAttempts} 次）: ${e.message}"
                    echo "[pgyerUpload] ${result.error}"
                }

                if (giveUp) {
                    break
                }
                // 网关与上游超时通常持续数十秒，间隔太短等于重复撞同一个故障窗口，故按次数递增退避。
                if (attempt < maxAttempts) {
                    int backoffSeconds = attempt * 15
                    echo "[pgyerUpload] ${backoffSeconds} 秒后重试…"
                    sleep(time: backoffSeconds, unit: 'SECONDS')
                }
            }
        }
    }

    if (!uploaded) {
        echo "[pgyerUpload] 上传结束但未成功: ${result.error}"
    }
    return result
}

/**
 * 第 1 步：取预上传凭证。请求体只有几 KB，走蒲公英网关无压力。
 *
 * @param noProxyOpt curl 的代理绕过参数，可为空串
 * @param buildType  蒲公英应用类型
 * @return parsePgyerCosToken 的结果
 */
def fetchCosToken(String noProxyOpt, String buildType) {
    // 先清空落盘文件再请求：避免重试时读到上一次的残留响应。
    // 末尾 `|| true` 吞掉 curl 退出码是有意为之：curl 非零退出会让 sh 步骤直接抛异常，
    // -w 写出的 http_code 就跟着丢了，而它是区分网关超时、鉴权失败、被拒的唯一依据。
    String httpCode = sh(
        script: """
            : > pgyer_cos_token.json
            curl -sS ${noProxyOpt} \
                --connect-timeout 15 --max-time 60 \
                -o pgyer_cos_token.json -w '%{http_code}' \
                --form-string "_api_key=\${PGYER_API_KEY}" \
                --form-string "buildType=${buildType}" \
                --form-string "buildUpdateDescription=\${PGYER_BUILD_DESC}" \
                https://api.pgyer.com/apiv2/app/getCOSToken || true
        """,
        returnStdout: true
    ).trim()

    String body = readFile('pgyer_cos_token.json').trim()
    if (httpCode != '200') {
        // 非 200 时响应体多为网关的 HTML 错误页，截断打印足以判断是网关问题还是接口问题。
        error "getCOSToken HTTP ${httpCode}，响应片段: ${body.take(300)}"
    }
    Map token = parsePgyerCosToken(body)
    if (token.code != 0) {
        error "getCOSToken 返回失败: code=${token.code} message=${token.message}"
    }
    echo "[pgyerUpload] 已取得预上传凭证，endpoint=${token.endpoint}，buildKey=${token.key}"
    return token
}

/**
 * 第 2 步：把安装包直传腾讯云 COS。
 *
 * @param noProxyOpt     curl 的代理绕过参数，可为空串
 * @param token          fetchCosToken 的结果
 * @param timeoutSeconds 单次上传的 curl 超时
 * @return ok（是否 204 成功）、retry（失败是否值得重传）、httpCode、
 *         stat（curl 统计串：http_code / 耗时秒 / 已传字节数 / 平均速度）、body（失败时的响应片段）
 */
def uploadToCos(String noProxyOpt, Map token, int timeoutSeconds) {
    Map params = token.params ?: [:]
    if (!params) {
        error "getCOSToken 未返回 params，无法构造 COS 上传表单"
    }

    // 按实际返回的字段全量透传，不挑字段：蒲公英当前返回 key / signature / x-cos-security-token，
    // 后续若增减签名字段无需改这里。
    //
    // 每个字段先落到一个环境变量里，变量的值就是「字段名=字段值」整体，curl 参数写成
    // --form-string "${变量}"，由 shell 带引号展开，避免签名里的特殊字符被拆词或被当成 shell 语法。
    // 必须用 --form-string 而不能用 -F：signature 里含分号，-F 会在分号处截断（详见文件头说明）。
    List<String> formEnv = []
    List<String> formArgs = []
    int index = 0
    params.each { name, value ->
        String varName = "PGYER_COS_FORM_${index}"
        formEnv << "${varName}=${name}=${value}"
        formArgs << ('--form-string "${' + varName + '}"')
        index++
    }
    echo "[pgyerUpload] COS 表单字段: ${params.keySet().join(', ')}"

    String stat = ''
    withEnv(formEnv + ["PGYER_COS_ENDPOINT=${token.endpoint}"]) {
        // file 字段必须排在整个表单最后，这是 COS POST Object 的硬性要求；成功返回 204 No Content。
        stat = sh(
            script: """
                : > pgyer_cos_upload.log
                curl -sS ${noProxyOpt} \
                    --connect-timeout 15 --max-time ${timeoutSeconds} \
                    -o pgyer_cos_upload.log \
                    -w '%{http_code} %{time_total} %{size_upload} %{speed_upload}' \
                    ${formArgs.join(' ')} \
                    --form-string "x-cos-meta-file-name=\${PGYER_ARTIFACT}" \
                    -F "file=@\${PGYER_ARTIFACT}" \
                    "\${PGYER_COS_ENDPOINT}" || true
            """,
            returnStdout: true
        ).trim()
    }

    // 四个值依次为 http_code、总耗时(秒)、已上传字节数、平均上传速度(B/s)，失败时据此区分「超时」与「被拒」。
    echo "[pgyerUpload] COS 上传统计（http_code / 耗时s / 字节数 / 速度B/s）: ${stat}"
    String httpCode = stat.tokenize(' ')[0] ?: '000'
    return [
        ok      : httpCode == '204',
        // 4xx 是签名或权限被拒，重传同一个包只会再白传几十 MB，故标记为不值得重试。
        retry   : !httpCode.startsWith('4'),
        stat    : stat,
        httpCode: httpCode,
        body    : httpCode == '204' ? '' : readFile('pgyer_cos_upload.log').trim().take(300)
    ]
}

/**
 * 第 3 步：轮询发布状态。COS 上传成功只代表文件已就位，发布由蒲公英后台队列异步完成。
 *
 * @param noProxyOpt          curl 的代理绕过参数，可为空串
 * @param buildKey            getCOSToken 返回的 key
 * @param maxPolls            最多轮询次数
 * @param pollIntervalSeconds 每次轮询间隔秒数
 * @return done（已确认发布完成）、failed（蒲公英侧明确发布失败）、lastInfo（最后一次状态说明），
 *         以及发布完成时的 buildKey / buildShortcutUrl / buildQRCodeURL。
 *         failed 与 done 都由调用方判定为「无需重传」，故这里返回标志位而不直接抛异常。
 */
def waitForPublish(String noProxyOpt, String buildKey, int maxPolls, int pollIntervalSeconds) {
    String lastInfo = ''
    for (int poll = 1; poll <= maxPolls; poll++) {
        sleep(time: pollIntervalSeconds, unit: 'SECONDS')
        String httpCode = sh(
            script: """
                : > pgyer_build_info.json
                curl -sS ${noProxyOpt} -G \
                    --connect-timeout 15 --max-time 60 \
                    -o pgyer_build_info.json -w '%{http_code}' \
                    --data-urlencode "_api_key=\${PGYER_API_KEY}" \
                    --data-urlencode "buildKey=${buildKey}" \
                    https://api.pgyer.com/apiv2/app/buildInfo || true
            """,
            returnStdout: true
        ).trim()

        if (httpCode != '200') {
            lastInfo = "buildInfo HTTP ${httpCode}"
            echo "[pgyerUpload] 第 ${poll}/${maxPolls} 次查询发布状态: ${lastInfo}"
            continue
        }

        Map info = parsePgyerBuildInfo(readFile('pgyer_build_info.json').trim())
        if (info.code == 0) {
            return [
                done            : true,
                failed          : false,
                lastInfo        : '',
                buildKey        : info.buildKey,
                buildShortcutUrl: info.buildShortcutUrl,
                buildQRCodeURL  : info.buildQRCodeURL
            ]
        }
        if (info.code == 1216) {
            // 1216 为蒲公英侧发布失败（如安装包解析不通过），重传同一个包无意义。
            return [
                done            : false,
                failed          : true,
                lastInfo        : "code=1216 message=${info.message}",
                buildKey        : info.buildKey,
                buildShortcutUrl: '',
                buildQRCodeURL  : ''
            ]
        }
        lastInfo = "code=${info.code} message=${info.message}"
        echo "[pgyerUpload] 第 ${poll}/${maxPolls} 次查询发布状态: ${lastInfo}"
    }
    return [done: false, failed: false, lastInfo: lastInfo, buildKey: '', buildShortcutUrl: '', buildQRCodeURL: '']
}

// 以下两个解析方法必须标 @NonCPS：JsonSlurper 产出的 LazyMap 不可序列化，
// 跨 sh / sleep 等步骤持有会抛 NotSerializableException，故在方法内就地摘成普通 Map 后返回。

/**
 * 解析 getCOSToken 响应。
 *
 * @param body getCOSToken 的响应正文
 * @return code、message、endpoint（COS 上传地址）、key（后续轮询用的 buildKey）、
 *         params（直传 COS 所需的全部签名字段，原样透传，键值均为 String）
 */
@NonCPS
def parsePgyerCosToken(String body) {
    def json = new groovy.json.JsonSlurper().parseText(body)
    def rawParams = json?.data?.params ?: [:]
    // 不挑字段：COS 要求 policy / q-* 等签名字段全部原样出现在上传表单里，
    // 且蒲公英文档未列全，故整体转成普通 Map（LazyMap 不可跨步骤持有）。
    Map params = [:]
    rawParams.each { key, value ->
        params[key.toString()] = (value == null ? '' : value.toString())
    }
    return [
        // 不能写成 (json?.code ?: -1)：Groovy 把 0 当假值，成功响应的 code=0 会被翻成 -1。
        code    : (json?.code == null ? -1 : json.code) as Integer,
        message : (json?.message ?: '').toString(),
        endpoint: (json?.data?.endpoint ?: '').toString(),
        key     : (json?.data?.key ?: '').toString(),
        params  : params
    ]
}

/**
 * 解析 buildInfo 响应。
 *
 * @param body buildInfo 的响应正文
 * @return code（0 发布完成，1216 发布失败，其余为发布中）、message，以及发布完成时的
 *         buildKey / buildShortcutUrl / buildQRCodeURL
 */
@NonCPS
def parsePgyerBuildInfo(String body) {
    def json = new groovy.json.JsonSlurper().parseText(body)
    return [
        code            : (json?.code == null ? -1 : json.code) as Integer,
        message         : (json?.message ?: '').toString(),
        buildKey        : (json?.data?.buildKey ?: '').toString(),
        buildShortcutUrl: (json?.data?.buildShortcutUrl ?: '').toString(),
        buildQRCodeURL  : (json?.data?.buildQRCodeURL ?: '').toString()
    ]
}
