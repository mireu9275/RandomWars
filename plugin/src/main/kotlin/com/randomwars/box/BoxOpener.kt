package com.randomwars.box

import com.randomwars.Text
import com.randomwars.weapon.Grade
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.util.UUID

/**
 * 랜덤상자 개봉: 우클릭 → 1개 소모 → 룰렛 연출 → 결과 지급.
 * 결과는 소모 시점에 미리 정해 두고, 연출은 보여주기만 한다.
 */
class BoxOpener(
    private val plugin: Plugin,
    private val loot: LootTable,
    private val rollTicks: () -> Int,
) : Listener {

    private class Rolling(val result: LootResult, val task: BukkitTask)

    private val rolling = mutableMapOf<UUID, Rolling>()

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        // 보급 상자처럼 먼저 처리된 블록 클릭이면 상자를 열지 않는다
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.DENY) return
        val hand = event.hand ?: return
        val player = event.player
        val item = player.inventory.getItem(hand)
        val type = loot.boxTypeOf(item) ?: return
        event.isCancelled = true

        if (player.uniqueId in rolling) return
        if (player.inventory.storageContents.none { it == null || it.isEmpty }) {
            player.sendActionBar(Text.mm("<red>인벤토리에 빈 칸이 있어야 상자를 열 수 있습니다."))
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        item.amount -= 1
        player.inventory.setItem(hand, item.takeIf { it.amount > 0 })
        start(player, type, loot.roll(type))
    }

    private fun start(player: Player, type: BoxType, result: LootResult) {
        val total = rollTicks().coerceAtLeast(1)
        var tick = 0
        var nextFlip = 0
        var interval = 1
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            if (tick >= total) {
                finish(player, type)
                return@Runnable
            }
            // 처음엔 빠르게, 끝으로 갈수록 느리게 이름이 바뀐다.
            if (tick >= nextFlip) {
                showTitle(player, Text.mm(loot.randomLabel()), Text.mm("<gray>${type.koreanName} 랜덤상자 개봉 중..."))
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.4f, 1.2f + tick.toFloat() / total)
                interval = 1 + (tick * 4) / total
                nextFlip = tick + interval
            }
            tick++
        }, 0L, 1L)
        rolling[player.uniqueId] = Rolling(result, task)
    }

    private fun finish(player: Player, type: BoxType) {
        val state = rolling.remove(player.uniqueId) ?: return
        state.task.cancel()
        val result = state.result
        showTitle(player, Text.mm(result.label), Text.mm("<gray>획득!"))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f)
        give(player, result)

        if (result is LootResult.Weapon && result.weapon.grade == Grade.LEGENDARY) {
            Bukkit.broadcast(Text.prefix("<yellow>${player.name}</yellow>님이 ${type.koreanName} 랜덤상자에서 <gold>[전설]</gold> ${result.label}<reset>을(를) 얻었습니다!"))
            Bukkit.getOnlinePlayers().forEach { it.playSound(it.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f) }
        }
    }

    private fun give(player: Player, result: LootResult) {
        val leftover = player.inventory.addItem(result.createItem())
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun showTitle(player: Player, title: Component, subtitle: Component) {
        player.showTitle(Title.title(title, subtitle, Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))))
    }

    /** 접속 종료 중이면 연출을 건너뛰고 바로 지급한다 (상자만 사라지는 일이 없도록). */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val state = rolling.remove(event.player.uniqueId) ?: return
        state.task.cancel()
        give(event.player, state.result)
    }

    /** 연출 중 사망하면 결과도 함께 소멸한다 (규칙 5). */
    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        rolling.remove(event.player.uniqueId)?.task?.cancel()
    }

    /** 혹시 설치 가능한 재질로 바뀌어도 상자는 놓을 수 없다. */
    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (loot.boxTypeOf(event.itemInHand) != null) event.isCancelled = true
    }

    fun shutdown() {
        // 서버 종료/리로드 시 진행 중인 개봉은 즉시 지급
        for ((uuid, state) in rolling.toMap()) {
            state.task.cancel()
            Bukkit.getPlayer(uuid)?.let { give(it, state.result) }
        }
        rolling.clear()
    }
}
