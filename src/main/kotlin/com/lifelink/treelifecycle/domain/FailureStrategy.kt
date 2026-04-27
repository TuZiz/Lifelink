package com.lifelink.treelifecycle.domain

enum class FailureStrategy {
    CANCEL,
    RETRY,
    DROP_SAPLING,
    RECORD_AND_RECOVER
}
