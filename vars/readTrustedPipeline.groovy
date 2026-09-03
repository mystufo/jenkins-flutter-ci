/**
 * 从同仓库 SCM 读取真正的 Declarative Pipeline 源码，并去掉顶部 @Library 注解。
 * 供入口 Jenkinsfile evaluate 使用（入口已加载共享库）。
 */
def call(String pipelineFile) {
    String path = (pipelineFile ?: '').toString().trim()
    if (!path) {
        error '[readTrustedPipeline] pipelineFile 不能为空'
    }
    def text = readTrusted(path)
    def kept = []
    for (line in text.readLines()) {
        def trimmed = line.trim()
        if (trimmed.startsWith('@Library') || trimmed == '_') {
            continue
        }
        kept.add(line)
    }
    return kept.join('\n') + '\n'
}
