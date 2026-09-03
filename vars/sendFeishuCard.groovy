/**
 * 向飞书群机器人发送一条「消息卡片」（interactive card）。
 *
 * Webhook 地址不写在源码里，而是从 Jenkins「Secret text」凭据读取，凭据值为完整的
 * https://open.feishu.cn/open-apis/bot/v2/hook/xxxx 地址。凭据 ID 的取值优先级：
 *   1. 调用时传入 credentialsId；
 *   2. 流水线 environment 里的 FEISHU_WEBHOOK_CREDENTIALS_ID；
 *   3. 默认 'feishu-webhook'。
 *
 * 用法：
 *   sendFeishuCard(card: [
 *       header  : [title: [tag: 'plain_text', content: '构建完成'], template: 'green'],
 *       elements: [[tag: 'markdown', content: '正文'], ...]
 *   ])
 *
 * 说明：
 *   - 必须在 node / agent 上下文中调用（内部要写临时文件并执行 curl）；
 *   - 消息体先落到文件再用 curl -d @file 发送，避免 shell 引号转义问题；
 *   - 发送失败不抛异常，只打印日志，避免通知故障影响构建结果。
 */
def call(Map config = [:]) {
    def card = config.card
    if (!card) {
        error '[sendFeishuCard] 缺少 card 参数'
    }
    String credentialsId = (config.credentialsId ?: env.FEISHU_WEBHOOK_CREDENTIALS_ID ?: 'feishu-webhook').toString().trim()
    String payload = groovy.json.JsonOutput.toJson([msg_type: 'interactive', card: card])
    String payloadFile = 'feishu_card_payload.json'

    try {
        withCredentials([string(credentialsId: credentialsId, variable: 'FEISHU_WEBHOOK')]) {
            writeFile file: payloadFile, text: payload
            sh '''
                curl -s -X POST \
                    -H 'Content-Type: application/json' \
                    -d @feishu_card_payload.json \
                    "${FEISHU_WEBHOOK}"
                echo ""
            '''
        }
        echo '[sendFeishuCard] 飞书消息已发送'
    } catch (Exception e) {
        echo "[sendFeishuCard] 飞书消息发送失败（不阻断流水线）：${e.message}"
    }
}
