@Library('jenkins-flutter-ci') _

// 入口必须始终能通过 Groovy 编译。真正的 pipeline {} 在 pipelines/ 下，
// 这样脚本语法错误时仍能 catch 并补发飞书（Declarative 的 post { failure } 此时不会跑）。
// webhookCredentialsId：飞书 Webhook 的 Secret text 凭据 ID，需与 pipelines/ 里 environment 的 FEISHU_WEBHOOK_CREDENTIALS_ID 一致。
try {
    evaluate(readTrustedPipeline('pipelines/mr_build_android.groovy'))
} catch (err) {
    notifyPipelineCompileFailure(
        err: err,
        webhookCredentialsId: 'feishu-webhook',
        failureTitle: 'Android APK 构建失败',
        platform: 'Android'
    )
    throw err
}
