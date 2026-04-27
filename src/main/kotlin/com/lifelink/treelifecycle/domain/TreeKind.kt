package com.lifelink.treelifecycle.domain

import org.bukkit.Material

enum class TreeKind(
    val key: String,
    private val logMaterialNames: Set<String>,
    private val leafMaterialNames: Set<String>,
    private val saplingMaterialName: String,
    val supportsFourSaplings: Boolean,
    val requiresFourSaplings: Boolean
) {
    OAK(
        "oak",
        setOf("OAK_LOG"),
        setOf("OAK_LEAVES"),
        "OAK_SAPLING",
        supportsFourSaplings = false,
        requiresFourSaplings = false
    ),
    BIRCH(
        "birch",
        setOf("BIRCH_LOG"),
        setOf("BIRCH_LEAVES"),
        "BIRCH_SAPLING",
        supportsFourSaplings = false,
        requiresFourSaplings = false
    ),
    SPRUCE(
        "spruce",
        setOf("SPRUCE_LOG"),
        setOf("SPRUCE_LEAVES"),
        "SPRUCE_SAPLING",
        supportsFourSaplings = true,
        requiresFourSaplings = false
    ),
    JUNGLE(
        "jungle",
        setOf("JUNGLE_LOG"),
        setOf("JUNGLE_LEAVES"),
        "JUNGLE_SAPLING",
        supportsFourSaplings = true,
        requiresFourSaplings = false
    ),
    ACACIA(
        "acacia",
        setOf("ACACIA_LOG"),
        setOf("ACACIA_LEAVES"),
        "ACACIA_SAPLING",
        supportsFourSaplings = false,
        requiresFourSaplings = false
    ),
    DARK_OAK(
        "dark_oak",
        setOf("DARK_OAK_LOG"),
        setOf("DARK_OAK_LEAVES"),
        "DARK_OAK_SAPLING",
        supportsFourSaplings = true,
        requiresFourSaplings = true
    ),
    MANGROVE(
        "mangrove",
        setOf("MANGROVE_LOG"),
        setOf("MANGROVE_LEAVES"),
        "MANGROVE_PROPAGULE",
        supportsFourSaplings = false,
        requiresFourSaplings = false
    ),
    CHERRY(
        "cherry",
        setOf("CHERRY_LOG"),
        setOf("CHERRY_LEAVES"),
        "CHERRY_SAPLING",
        supportsFourSaplings = false,
        requiresFourSaplings = false
    ),
    PALE_OAK(
        "pale_oak",
        setOf("PALE_OAK_LOG"),
        setOf("PALE_OAK_LEAVES"),
        "PALE_OAK_SAPLING",
        supportsFourSaplings = false,
        requiresFourSaplings = false
    );

    val logMaterials: Set<Material> by lazy { logMaterialNames.mapNotNull(Material::matchMaterial).toSet() }

    val leafMaterials: Set<Material> by lazy { leafMaterialNames.mapNotNull(Material::matchMaterial).toSet() }

    private val saplingMaterialOrNull: Material? by lazy { Material.matchMaterial(saplingMaterialName) }

    val saplingMaterial: Material
        get() = saplingMaterialOrNull ?: error("Tree kind $name is not supported by this server version")

    val isAvailable: Boolean
        get() = logMaterials.isNotEmpty() && leafMaterials.isNotEmpty() && saplingMaterialOrNull != null

    fun isLog(material: Material): Boolean = material in logMaterials

    fun isLeaf(material: Material): Boolean = material in leafMaterials

    companion object {
        private val byLog: Map<Material, TreeKind> by lazy {
            entries.filter { it.isAvailable }.flatMap { kind -> kind.logMaterials.map { it to kind } }.toMap()
        }
        private val bySapling: Map<Material, TreeKind> by lazy {
            entries.filter { it.isAvailable }.associateBy { it.saplingMaterial }
        }

        fun fromLog(material: Material): TreeKind? = byLog[material]

        fun fromSapling(material: Material): TreeKind? = bySapling[material]

        fun isKnownSapling(material: Material): Boolean = material in bySapling
    }
}
