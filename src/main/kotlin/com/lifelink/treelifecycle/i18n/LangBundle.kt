package com.lifelink.treelifecycle.i18n

data class MessageDefinition(
    val type: MessageTarget,
    val text: String,
    val title: String?,
    val subtitle: String?
)

data class LangBundle(
    val locale: String,
    val prefix: String,
    val messages: Map<String, MessageDefinition>,
    val treeTypes: Map<String, String>
) {
    fun message(key: String): MessageDefinition =
        messages[key] ?: MessageDefinition(MessageTarget.CHAT, "<prefix><#FF5555>Missing message: $key</#FF5555>", null, null)

    fun treeTypeName(key: String): String = treeTypes[key] ?: key
}
