package com.lifelink.treelifecycle.domain

enum class ReplantTaskStatus {
    DETECTING,
    RESERVED,
    CUTTING_CONFIRMED,
    REPLANT_PENDING,
    REPLANT_SCHEDULED,
    REPLANTED,
    FAILED,
    ROLLED_BACK;

    fun isTerminal(): Boolean = this == REPLANTED || this == FAILED || this == ROLLED_BACK
}
