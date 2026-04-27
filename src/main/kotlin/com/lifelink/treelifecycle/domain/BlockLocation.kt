package com.lifelink.treelifecycle.domain

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import java.util.UUID

data class BlockLocation(
    val worldId: UUID,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int
) {
    val chunkX: Int get() = Math.floorDiv(x, 16)
    val chunkZ: Int get() = Math.floorDiv(z, 16)

    fun relative(dx: Int, dy: Int, dz: Int): BlockLocation =
        copy(x = x + dx, y = y + dy, z = z + dz)

    fun horizontalDistanceSquared(other: BlockLocation): Int {
        val dx = x - other.x
        val dz = z - other.z
        return dx * dx + dz * dz
    }

    fun toLocation(world: org.bukkit.World): Location =
        Location(world, x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        fun from(block: Block): BlockLocation =
            BlockLocation(block.world.uid, block.world.name, block.x, block.y, block.z)

        fun from(state: BlockState): BlockLocation {
            val location = state.location
            return BlockLocation(location.world!!.uid, location.world!!.name, location.blockX, location.blockY, location.blockZ)
        }

        fun from(location: Location): BlockLocation =
            BlockLocation(location.world!!.uid, location.world!!.name, location.blockX, location.blockY, location.blockZ)
    }
}
