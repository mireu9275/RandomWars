package com.randomwars.zone

import com.randomwars.Text
import com.randomwars.box.BoxType
import com.randomwars.box.LootTable
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

/**
 * 로비 ↔ 플레이존 이동과 한 목숨 단위 상태(입장 보상 여부, 입장 무적)를 관리한다.
 */
class ZoneService(
    private val plugin: Plugin,
    private val config: ZoneConfig,
    private val loot: LootTable,
) {
    private val entryRewardedKey = NamespacedKey(plugin, "entry_rewarded")
    private val invulnerableUntil = mutableMapOf<UUID, Long>()
    private val pendingLobby = mutableMapOf<UUID, BukkitTask>()
    private val warping = mutableSetOf<UUID>()

    lateinit var lobby: World
        private set
    lateinit var play: World
        private set

    fun setupWorlds() {
        lobby = Bukkit.getWorld(config.lobbyWorld) ?: WorldCreator(config.lobbyWorld)
            .type(WorldType.FLAT)
            .generatorSettings("""{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}""")
            .generateStructures(false)
            .createWorld()!!
        lobby.pvp = false
        lobby.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        lobby.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        lobby.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        lobby.time = 6000

        play = Bukkit.getWorld(config.playWorld) ?: WorldCreator(config.playWorld).createWorld()!!
        play.pvp = true
        play.setGameRule(GameRule.KEEP_INVENTORY, false)
        play.setGameRule(GameRule.DO_MOB_SPAWNING, config.mobSpawning)
        val (cx, cz) = config.center
        play.worldBorder.center = Location(play, cx, 0.0, cz)
        play.worldBorder.size = config.size
    }

    fun lobbySpawn(): Location = config.lobbySpawn?.let { Location(lobby, it[0], it[1], it[2]) }
        ?: lobby.spawnLocation.toCenterLocation().apply { y = lobby.getHighestBlockYAt(blockX, blockZ) + 1.0 }

    fun isInPlay(player: Player) = player.world == play

    // ---------- 한 목숨 상태 ----------

    fun isEntryRewarded(player: Player) =
        player.persistentDataContainer.has(entryRewardedKey, PersistentDataType.BYTE)

    /** 사망 시 호출: 다음 입장 때 다시 상자를 받는다. */
    fun resetLife(player: Player) {
        player.persistentDataContainer.remove(entryRewardedKey)
        invulnerableUntil.remove(player.uniqueId)
        cancelLobby(player)
    }

    fun isInvulnerable(player: Player): Boolean {
        val until = invulnerableUntil[player.uniqueId] ?: return false
        if (System.currentTimeMillis() < until) return true
        invulnerableUntil.remove(player.uniqueId)
        return false
    }

    fun clearInvulnerable(player: Player) {
        invulnerableUntil.remove(player.uniqueId)
    }

    // ---------- 입장 ----------

    fun enter(player: Player) {
        if (isInPlay(player)) {
            player.sendMessage(Text.prefix("<red>이미 플레이존에 있습니다."))
            return
        }
        if (!warping.add(player.uniqueId)) return
        player.sendActionBar(Text.mm("<gray>착지 지점을 찾는 중..."))
        findWarp().thenAccept { target ->
            if (!player.isOnline) {
                warping.remove(player.uniqueId)
                return@thenAccept
            }
            player.teleportAsync(target).thenAccept { ok ->
                warping.remove(player.uniqueId)
                if (ok) onEntered(player)
            }
        }
    }

    private fun onEntered(player: Player) {
        invulnerableUntil[player.uniqueId] = System.currentTimeMillis() + config.invulnerableSeconds * 1000L
        player.fallDistance = 0f
        player.showTitle(Title.title(
            Text.mm("<red>플레이존 입장"),
            Text.mm("<gray>${config.invulnerableSeconds}초 동안 무적입니다"),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500)),
        ))
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)

        if (!isEntryRewarded(player)) {
            player.persistentDataContainer.set(entryRewardedKey, PersistentDataType.BYTE, 1)
            val boxes = loot.createBox(BoxType.NORMAL, config.entryBoxes)
            player.inventory.addItem(boxes).values.forEach { player.world.dropItemNaturally(player.location, it) }
            player.sendMessage(Text.prefix("입장 보상으로 <white>랜덤상자 ${config.entryBoxes}개</white>를 받았습니다."))
        } else {
            player.sendMessage(Text.prefix("<gray>이번 목숨의 입장 보상은 이미 받았습니다."))
        }
    }

    // ---------- 랜덤 워프 ----------

    private fun findWarp(): CompletableFuture<Location> {
        val others = play.players.map { it.location }
        val farEnough = { loc: Location -> others.none { it.distanceSquared(loc) < config.minPlayerDistance * config.minPlayerDistance } }
        val points = config.spawnPoints(play)

        if (config.warpMode == WarpMode.POINTS && points.isNotEmpty()) {
            val pick = points.shuffled().firstOrNull(farEnough) ?: points.random()
            return CompletableFuture.completedFuture(pick)
        }
        val result = CompletableFuture<Location>()
        tryRandom(80, farEnough, result) {
            // 랜덤 좌표를 못 찾으면 후보 지점, 그마저 없으면 월드 스폰
            points.shuffled().firstOrNull(farEnough) ?: points.randomOrNull() ?: play.spawnLocation
        }
        return result
    }

    private fun tryRandom(
        attemptsLeft: Int,
        farEnough: (Location) -> Boolean,
        result: CompletableFuture<Location>,
        fallback: () -> Location,
    ) {
        if (attemptsLeft <= 0) {
            result.complete(fallback())
            return
        }
        val border = play.worldBorder
        val half = border.size / 2 - 8
        val x = (border.center.x + Random.nextDouble(-half, half)).toInt()
        val z = (border.center.z + Random.nextDouble(-half, half)).toInt()
        play.getChunkAtAsync(x shr 4, z shr 4).thenAccept {
            val loc = safeSurface(x, z)
            if (loc != null && farEnough(loc)) result.complete(loc)
            else tryRandom(attemptsLeft - 1, farEnough, result, fallback)
        }
    }

    /** 단단한 블록 위, 머리 위 2칸이 비어 있고 액체가 아닌 곳 */
    fun safeSurface(x: Int, z: Int): Location? {
        val y = play.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        if (config.maxWarpY > 0 && y + 1 > config.maxWarpY) return null
        val ground = play.getBlockAt(x, y, z)
        if (!ground.type.isSolid || ground.isLiquid) return null
        val feet = ground.getRelative(0, 1, 0)
        val head = ground.getRelative(0, 2, 0)
        if (!feet.isPassable || !head.isPassable || feet.isLiquid || head.isLiquid) return null
        return Location(play, x + 0.5, y + 1.0, z + 0.5, Random.nextFloat() * 360f, 0f)
    }

    /**
     * 지붕 아래까지 포함해 지면 근처에서 설 수 있는 바닥 (건물 1층 안쪽도 된다).
     * max-warp-y 가 있으면 그 높이부터, 없으면 가장 높은 블록부터 아래로 찾는다.
     */
    fun groundFloor(x: Int, z: Int): Location? {
        val top = if (config.maxWarpY > 0) config.maxWarpY else play.getHighestBlockYAt(x, z) + 1
        // 너무 깊이 내려가면 지하 동굴에 놓이므로 12칸까지만 본다
        for (y in top downTo maxOf(play.minHeight + 1, top - 12)) {
            val ground = play.getBlockAt(x, y - 1, z)
            val feet = play.getBlockAt(x, y, z)
            val head = play.getBlockAt(x, y + 1, z)
            if (ground.type.isSolid && !ground.isLiquid && feet.isPassable && !feet.isLiquid && head.isPassable && !head.isLiquid) {
                return Location(play, x + 0.5, y.toDouble(), z + 0.5)
            }
        }
        return null
    }

    // ---------- 로비 복귀 ----------

    fun sendToLobby(player: Player) {
        player.teleportAsync(lobbySpawn())
    }

    /** /로비: 제자리에서 wait-seconds 동안 기다리면 소지품을 유지한 채 로비로 돌아간다. */
    fun requestLobby(player: Player, blockedReason: () -> String?) {
        if (!isInPlay(player)) {
            player.sendMessage(Text.prefix("<red>플레이존에서만 쓸 수 있습니다."))
            return
        }
        blockedReason()?.let {
            player.sendMessage(Text.prefix("<red>$it"))
            return
        }
        if (player.uniqueId in pendingLobby) return
        val start = player.location.clone()
        var remaining = config.lobbyWaitSeconds
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val block = blockedReason()
            if (!player.isOnline || player.location.world != start.world || player.location.distanceSquared(start) > 0.5 || block != null) {
                cancelLobby(player)
                player.sendActionBar(Text.mm("<red>${block ?: "움직여서 로비 이동이 취소되었습니다."}"))
                return@Runnable
            }
            if (remaining <= 0) {
                cancelLobby(player)
                sendToLobby(player)
                player.sendMessage(Text.prefix("로비로 돌아왔습니다. 소지품은 그대로입니다."))
                return@Runnable
            }
            player.sendActionBar(Text.mm("<yellow>${remaining}초 후 로비로 이동합니다. 움직이면 취소됩니다."))
            remaining--
        }, 0L, 20L)
        pendingLobby[player.uniqueId] = task
    }

    fun cancelLobby(player: Player) {
        pendingLobby.remove(player.uniqueId)?.cancel()
    }
}
