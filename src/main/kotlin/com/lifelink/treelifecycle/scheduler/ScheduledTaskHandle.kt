package com.lifelink.treelifecycle.scheduler

fun interface ScheduledTaskHandle {
    fun cancel()

    companion object {
        val NOOP = ScheduledTaskHandle {}
    }
}
