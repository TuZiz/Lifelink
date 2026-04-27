package com.lifelink.treelifecycle.i18n

enum class MessageTarget {
    CHAT,
    ACTIONBAR,
    TITLE;

    companion object {
        fun parse(raw: String?): MessageTarget =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CHAT
    }
}
