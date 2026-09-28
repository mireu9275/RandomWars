package com.randomwars.box

import com.randomwars.Keys
import com.randomwars.Text
import com.randomwars.weapon.Grade
import com.randomwars.weapon.WeaponDefinition
import com.randomwars.weapon.WeaponRegistry
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionType
import java.io.File
import java.util.logging.Logger
import kotlin.random.Random

enum class BoxType(val id: String, val koreanName: String) {
    NORMAL("normal", "일반"),
    SUPPLY("supply", "보급");

    companion object {
        fun parse(value: String): BoxType? =
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) || it.koreanName == value }
    }
}

data class BoxSettings(
    val name: String,
    val model: NamespacedKey?,
    val consumableChance: Double,
    val gradeWeights: Map<Grade, Double>,
)

data class Consumable(
    val material: Material,
    val amount: Int,
    val potion: PotionType?,
    val weight: Double,
) {
    fun createItem(): ItemStack {
        val item = ItemStack(material, amount)
        if (potion != null) item.editMeta(PotionMeta::class.java) { it.basePotionType = potion }
        return item
    }
}

sealed interface LootResult {
    /** 룰렛과 결과 안내에 쓰는 MiniMessage 문자열 */
    val label: String
    fun createItem(): ItemStack

    data class Weapon(val weapon: WeaponDefinition) : LootResult {
        override val label get() = "${weapon.grade.color}${weapon.name}"
        override fun createItem() = weapon.createItem()
    }

    data class Item(val consumable: Consumable) : LootResult {
        override val label get() = "<green><lang:${consumable.material.translationKey()}> x${consumable.amount}"
        override fun createItem() = consumable.createItem()
    }
}

class LootTable(private val logger: Logger, private val weapons: WeaponRegistry) {
    private var boxes: Map<BoxType, BoxSettings> = emptyMap()
    private var consumables: List<Consumable> = emptyList()

    fun load(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        boxes = BoxType.entries.associateWith { type ->
            val s = yaml.getConfigurationSection("boxes.${type.id}")
            val grades = Grade.entries.associateWith { s?.getDouble("grades.${it.name.lowercase()}", 0.0) ?: 0.0 }
            BoxSettings(
                name = s?.getString("name") ?: "${type.koreanName} 랜덤상자",
                model = s?.getString("model")?.let { NamespacedKey.fromString(it) },
                consumableChance = (s?.getDouble("consumable-chance", 0.0) ?: 0.0).coerceIn(0.0, 1.0),
                gradeWeights = grades,
            )
        }
        consumables = yaml.getMapList("consumables").mapNotNull { raw ->
            val material = Material.matchMaterial(raw["material"]?.toString() ?: "") ?: run {
                logger.warning("loot.yml: 알 수 없는 소모품 ${raw["material"]}")
                return@mapNotNull null
            }
            Consumable(
                material = material,
                amount = (raw["amount"] as? Number)?.toInt() ?: 1,
                potion = (raw["potion"] as? String)?.let { runCatching { PotionType.valueOf(it.uppercase()) }.getOrNull() },
                weight = (raw["weight"] as? Number)?.toDouble() ?: 1.0,
            )
        }
        for ((type, settings) in boxes) {
            for ((grade, weight) in settings.gradeWeights) {
                if (weight > 0 && weapons.ofGrade(grade).isEmpty()) {
                    logger.warning("loot.yml: ${type.koreanName} 상자의 ${grade.displayName} 등급에 무기가 없어 이 등급은 제외됩니다.")
                }
            }
        }
    }

    fun settings(type: BoxType): BoxSettings = boxes.getValue(type)

    /** 무기가 하나 이상 있는 등급만 남긴 가중치 */
    fun effectiveGradeWeights(type: BoxType): Map<Grade, Double> =
        settings(type).gradeWeights.filter { (grade, w) -> w > 0 && weapons.ofGrade(grade).isNotEmpty() }

    fun roll(type: BoxType, random: Random = Random.Default): LootResult {
        val settings = settings(type)
        if (consumables.isNotEmpty() && random.nextDouble() < settings.consumableChance) {
            return LootResult.Item(weighted(consumables, random) { it.weight })
        }
        val grades = effectiveGradeWeights(type).entries.toList()
        if (grades.isEmpty()) return LootResult.Item(consumables.firstOrNull() ?: Consumable(Material.BREAD, 1, null, 1.0))
        val grade = weighted(grades, random) { it.value }.key
        return LootResult.Weapon(weapons.ofGrade(grade).random(random))
    }

    /** 룰렛 연출용 아무 후보 */
    fun randomLabel(random: Random = Random.Default): String {
        val pool = weapons.all.toList()
        return if (pool.isEmpty()) "<gray>???" else pool.random(random).let { "${it.grade.color}${it.name}" }
    }

    fun createBox(type: BoxType, amount: Int = 1): ItemStack {
        val settings = settings(type)
        // PAPER 는 설치할 수 없는 아이템이라 상자를 블록으로 놓을 일이 없다.
        val item = ItemStack(Material.PAPER, amount)
        item.editMeta { meta ->
            meta.displayName(Text.item(settings.name))
            meta.lore(listOf(
                Text.item("<gray>손에 들고 <yellow>우클릭</yellow>하면 개봉합니다."),
                Text.item("<dark_gray>사망 시 소멸"),
            ))
            settings.model?.let { meta.itemModel = it }
            meta.persistentDataContainer.set(Keys.BOX_TIER, PersistentDataType.STRING, type.id)
        }
        return item
    }

    fun boxTypeOf(item: ItemStack?): BoxType? {
        if (item == null || item.isEmpty) return null
        val id = item.persistentDataContainer.get(Keys.BOX_TIER, PersistentDataType.STRING) ?: return null
        return BoxType.parse(id)
    }

    private fun <T> weighted(items: List<T>, random: Random, weight: (T) -> Double): T {
        val total = items.sumOf(weight)
        var r = random.nextDouble() * total
        for (item in items) {
            r -= weight(item)
            if (r < 0) return item
        }
        return items.last()
    }
}
