package com.randomwars.weapon

import com.randomwars.Keys
import com.randomwars.Text
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.logging.Logger

data class WeaponDefinition(
    val id: String,
    val name: String,
    val grade: Grade,
    val material: Material,
    val model: NamespacedKey?,
    val roles: List<String>,
) {
    fun createItem(): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Text.item("${grade.color}$name"))
            val lore = mutableListOf(Text.item("<gray>등급: ${grade.colored}"))
            if (roles.isNotEmpty()) lore += Text.item("<gray>역할: <white>${roles.joinToString(", ")}")
            meta.lore(lore)
            meta.isUnbreakable = true
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
            model?.let { meta.itemModel = it }
            meta.persistentDataContainer.set(Keys.WEAPON_ID, PersistentDataType.STRING, id)
        }
        return item
    }
}

class WeaponRegistry(private val logger: Logger) {
    private var weapons: Map<String, WeaponDefinition> = emptyMap()
    private var byGrade: Map<Grade, List<WeaponDefinition>> = emptyMap()

    val all: Collection<WeaponDefinition> get() = weapons.values

    fun load(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("weapons")
        val loaded = linkedMapOf<String, WeaponDefinition>()
        for (id in section?.getKeys(false).orEmpty()) {
            val s = section!!.getConfigurationSection(id) ?: continue
            val grade = Grade.parse(s.getString("grade", "")!!)
            val material = Material.matchMaterial(s.getString("material", "")!!)
            if (grade == null || material == null || !material.isItem) {
                logger.warning("weapons.yml: '$id' 의 grade 또는 material 이 잘못되어 건너뜁니다.")
                continue
            }
            loaded[id] = WeaponDefinition(
                id = id,
                name = s.getString("name", id)!!,
                grade = grade,
                material = material,
                model = s.getString("model")?.let { NamespacedKey.fromString(it) },
                roles = s.getStringList("roles"),
            )
        }
        weapons = loaded
        byGrade = loaded.values.groupBy { it.grade }
        logger.info("무기 ${loaded.size}종 로드: " + Grade.entries.joinToString { "${it.displayName} ${byGrade[it]?.size ?: 0}" })
    }

    operator fun get(id: String): WeaponDefinition? = weapons[id]

    fun ofGrade(grade: Grade): List<WeaponDefinition> = byGrade[grade].orEmpty()

    fun idOf(item: ItemStack?): String? =
        item?.persistentDataContainer?.get(Keys.WEAPON_ID, PersistentDataType.STRING)
}
