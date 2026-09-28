package com.randomwars.weapon

import com.randomwars.Keys
import com.randomwars.Text
import com.randomwars.skill.Parts
import com.randomwars.skill.SkillDefinition
import org.bukkit.configuration.ConfigurationSection
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
    val skill: SkillDefinition?,
    val shiftSkill: SkillDefinition?,
    val description: String?,
) {
    fun createItem(): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Text.item("${grade.color}$name"))
            val lore = mutableListOf(Text.item("<gray>등급: ${grade.colored}"))
            if (roles.isNotEmpty()) lore += Text.item("<gray>역할: <white>${roles.joinToString(", ")}")
            description?.let { lore += Text.item("<dark_gray>$it") }
            skill?.let { lore += Text.item("<yellow>우클릭</yellow> <white>${it.name}</white> <gray>(쿨다운 ${fmt(it.cooldownSeconds)}초)") }
            shiftSkill?.let { lore += Text.item("<yellow>웅크리기+우클릭</yellow> <white>${it.name}</white> <gray>(쿨다운 ${fmt(it.cooldownSeconds)}초)") }
            meta.lore(lore)
            // 쿨다운 표시를 무기마다 따로 하도록 쿨다운 그룹을 무기 id 로 둔다
            meta.setUseCooldown(meta.useCooldown.also {
                it.cooldownGroup = NamespacedKey("randomwars", "weapon/$id")
                it.cooldownSeconds = 0.0001f
            })
            meta.isUnbreakable = true
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
            model?.let { meta.itemModel = it }
            meta.persistentDataContainer.set(Keys.WEAPON_ID, PersistentDataType.STRING, id)
        }
        return item
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
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
                skill = parseSkill(id, s.getConfigurationSection("skill")),
                shiftSkill = parseSkill(id, s.getConfigurationSection("shift-skill")),
                description = s.getString("description"),
            )
        }
        weapons = loaded
        byGrade = loaded.values.groupBy { it.grade }
        logger.info("무기 ${loaded.size}종 로드: " + Grade.entries.joinToString { "${it.displayName} ${byGrade[it]?.size ?: 0}" })
    }

    private fun parseSkill(id: String, s: ConfigurationSection?): SkillDefinition? {
        if (s == null) return null
        return try {
            SkillDefinition(
                name = s.getString("name", "스킬")!!,
                cooldownSeconds = s.getDouble("cooldown", 10.0),
                range = s.getDouble("range", 10.0),
                parts = s.getMapList("parts").map { Parts.parse(it) },
            )
        } catch (e: Exception) {
            logger.warning("weapons.yml: '$id' 스킬을 읽지 못했습니다: ${e.message}")
            null
        }
    }

    operator fun get(id: String): WeaponDefinition? = weapons[id]

    fun ofGrade(grade: Grade): List<WeaponDefinition> = byGrade[grade].orEmpty()

    fun idOf(item: ItemStack?): String? =
        item?.persistentDataContainer?.get(Keys.WEAPON_ID, PersistentDataType.STRING)
}
