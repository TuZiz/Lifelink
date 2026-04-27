package com.lifelink.treelifecycle.api

import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.domain.ManagedTreeRecord
import com.lifelink.treelifecycle.domain.ReplantTaskRecord

interface LifeLinkApi {
    fun systemSaplingAt(location: BlockLocation): ManagedTreeRecord?

    fun managedTreeAt(location: BlockLocation): ManagedTreeRecord?

    fun playerTreeAt(location: BlockLocation): ManagedTreeRecord?

    fun activeTasks(): List<ReplantTaskRecord>
}
