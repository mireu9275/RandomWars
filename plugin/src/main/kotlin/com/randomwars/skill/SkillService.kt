package com.randomwars.skill

import com.randomwars.Text
import com.randomwars.weapon.WeaponRegistry
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

/**
 * 무기 스킬 발동: 우클릭 = 기본 스킬, 웅크리기+우클릭 = 보조 스킬(있으면).
 * 쿨다운은 무기·스킬별로 따로 돌고, 아이템 쿨다운 표시로 보여준다.
 */
class SkillService(
    private val logger: java.util.logging.Logger,
    private val debug: () -> Boolean,
    private val weapons: WeaponRegistry,
    private val effects: SkillEffects,
    private val projectiles: ProjectileTracker,
) : Listener {

    private val readyAt = mutableMapOf<String, Long>()

    private fun key(player: Player, weaponId: String, shift: Boolean) = "${player.uniqueId}:$weaponId:${if (shift) 2 else 1}"

    @EventHandler(priority = EventPriority.NORMAL)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        // 보급 상자 등 먼저 처리된 블록 클릭은 건너뛴다
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return
        val player = event.player
        val item = player.inventory.itemInMainHand
        val weapon = weapons[weapons.idOf(item) ?: return] ?: return
        val shift = player.isSneaking && weapon.shiftSkill != null
        val skill = (if (shift) weapon.shiftSkill else weapon.skill) ?: return

        // 활·방패 등 바닐라 우클릭 동작 대신 스킬만 나가게 한다
        event.isCancelled = true

        if (effects.isStunned(player)) {
            player.sendActionBar(Text.mm("<red>기절 중에는 스킬을 쓸 수 없습니다"))
            return
        }
        val k = key(player, weapon.id, shift)
        val left = (readyAt[k] ?: 0) - System.currentTimeMillis()
        if (left > 0) {
            player.sendActionBar(Text.mm("<gray>${skill.name} <red>${"%.1f".format(left / 1000.0)}초</red> 남음"))
            return
        }

        val target = findTarget(player, skill.range)
        if (skill.needsTarget && target == null) {
            player.sendActionBar(Text.mm("<gray>${skill.range.toInt()}칸 안에 조준한 대상이 없습니다"))
            player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1.4f)
            return
        }
        // 시전 시 효과 중심은 시전자 (넉백 파동 등). 투사체 명중 시에는 명중 지점이 중심이 된다
        val ctx = SkillContext(player, target, player.location, effects, projectiles)
        if (debug()) logger.info("[skill] ${player.name} ${weapon.id} ${skill.name} target=${target?.name}")
        skill.parts.forEach { it.apply(ctx) }

        val cooldownMs = (skill.cooldownSeconds * 1000).toLong()
        readyAt[k] = System.currentTimeMillis() + cooldownMs
        if (!shift) player.setCooldown(item, (skill.cooldownSeconds * 20).toInt())
        player.sendActionBar(Text.mm("<yellow>${skill.name}!"))
    }

    /** 시선 방향으로 가장 먼저 걸리는 살아 있는 대상 (조준 판정을 조금 넉넉하게) */
    private fun findTarget(player: Player, range: Double): LivingEntity? {
        val result = player.world.rayTraceEntities(player.eyeLocation, player.eyeLocation.direction, range, 0.6) {
            it != player && it is LivingEntity && !it.isDead && it.type.isAlive
        } ?: return null
        val entity = result.hitEntity as? LivingEntity ?: return null
        // 벽 너머 대상은 제외
        val eye = player.eyeLocation.toVector()
        val block = player.rayTraceBlocks(range)
        if (block != null && block.hitPosition.distanceSquared(eye) < result.hitPosition.distanceSquared(eye)) return null
        return entity
    }

    fun clear(uuid: UUID) {
        readyAt.keys.removeIf { it.startsWith(uuid.toString()) }
    }
}
