package com.lifelink.treelifecycle.service

import com.lifelink.treelifecycle.config.ConfigService
import com.lifelink.treelifecycle.domain.BlockLocation
import com.lifelink.treelifecycle.util.Permissions
import org.bukkit.block.Block
import org.bukkit.entity.Player

class TreeProtectionService(
    private val configService: ConfigService,
    private val lifecycleService: TreeLifecycleService
) {
    fun isProtectedSystemSapling(block: Block): Boolean =
        configService.current().protection.protectSystemSaplings &&
            lifecycleService.systemSaplingAt(BlockLocation.from(block)) != null

    fun isProtectedSystemSapling(location: BlockLocation): Boolean =
        configService.current().protection.protectSystemSaplings &&
            lifecycleService.systemSaplingAt(location) != null

    fun canBypassSapling(player: Player): Boolean =
        hasBypassPermission(player, Permissions.BYPASS_SAPLING_PROTECTION)

    fun canBypassManagedTree(player: Player): Boolean =
        hasBypassPermission(player, Permissions.BYPASS_MANAGED_TREE)

    private fun hasBypassPermission(player: Player, permission: String): Boolean {
        val hasPermission = player.hasPermission(permission) || player.hasPermission(Permissions.ADMIN)
        if (!hasPermission) return false
        return !configService.current().protection.bypassRequiresSneaking || player.isSneaking
    }
}
