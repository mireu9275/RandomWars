package com.randomwars.supply

import com.randomwars.Text
import com.randomwars.box.BoxType
import com.randomwars.box.LootTable
import com.randomwars.zone.ZoneService
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 규칙 4: 주기마다 무작위 지역 몇 곳에 보급 상자를 떨어뜨리고, 먼저 연 사람에게 보급 랜덤상자를 준다.
 * 보급 상자는 배리어 블록(클릭 판정) + 보급상자 모델을 씌운 ItemDisplay(외형, 발광)로 만든다.
 */
class SupplyService(
    private val plugin: Plugin,
    private val zones: ZoneService,
    private val loot: LootTable,
    private val regions: RegionStore,
    private val intervalSeconds: () -> Int,
    private val dropCount: () -> Int,
) : Listener {

    private class Drop(val region: Region, val block: Location, val display: ItemDisplay) {
        var yaw = 0f
    }

    private val drops = mutableListOf<Drop>()
    private val bars = mutableMapOf<UUID, BossBar>()
    private val stateFile = File(plugin.dataFolder, "supply-state.yml")
    private var nextAt = 0L
    private var task: BukkitTask? = null

    fun start() {
        cleanupFromLastRun()
        nextAt = System.currentTimeMillis() + intervalSeconds() * 1000L
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 10L, 10L)
    }

    fun stop() {
        task?.cancel()
        clearDrops()
        bars.forEach { (uuid, bar) -> Bukkit.getPlayer(uuid)?.hideBossBar(bar) }
        bars.clear()
    }

    fun secondsUntilNext() = ((nextAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    fun activeRegionNames() = drops.map { "${it.region.name}(${it.block.blockX} ${it.block.blockY} ${it.block.blockZ})" }

    // ---------- 주기 ----------

    private fun tick() {
        if (System.currentTimeMillis() >= nextAt) dropNow()
        for (drop in drops) {
            drop.yaw = (drop.yaw + 18f) % 360f
            drop.display.teleport(drop.display.location.apply { yaw = drop.yaw })
            drop.block.world.spawnParticle(Particle.END_ROD, drop.block.clone().add(0.5, 1.2, 0.5), 3, 0.3, 0.4, 0.3, 0.02)
        }
        updateBars()
    }

    /** 이전 보급을 치우고 새 보급을 떨어뜨린다. 다음 주기는 지금부터 다시 센다. */
    fun dropNow(): List<String> {
        clearDrops()
        nextAt = System.currentTimeMillis() + intervalSeconds() * 1000L
        val picked = regions.regions.shuffled().take(dropCount())
        for (region in picked) {
            val block = findSpot(region) ?: continue
            drops += place(region, block)
        }
        saveState()
        if (drops.isEmpty()) {
            if (regions.regions.isEmpty()) plugin.logger.warning("보급 지역이 없어 보급을 건너뜁니다. /rw region add 로 등록하세요.")
            return emptyList()
        }
        announce()
        return drops.map { it.region.name }
    }

    private fun findSpot(region: Region): Location? {
        val world = zones.play
        if (region.radius <= 0 && region.y != null) {
            return Location(world, region.x, region.y, region.z).block.location
        }
        repeat(40) {
            val x = (region.x + Random.nextInt(-region.radius, region.radius + 1)).toInt()
            val z = (region.z + Random.nextInt(-region.radius, region.radius + 1)).toInt()
            zones.groundFloor(x, z)?.let { return it.block.location }
        }
        // 바닥을 못 찾으면 중심의 가장 높은 곳
        val y = region.y ?: zones.groundFloor(region.x.toInt(), region.z.toInt())?.y
            ?: (world.getHighestBlockYAt(region.x.toInt(), region.z.toInt()) + 1.0)
        return Location(world, region.x, y, region.z).block.location
    }

    private fun place(region: Region, block: Location): Drop {
        // ItemDisplay 는 저장되지 않는 엔티티라 청크가 내려가면 사라진다. 보급이 있는 동안 청크를 붙잡아 둔다.
        block.chunk.addPluginChunkTicket(plugin)
        block.block.type = Material.BARRIER
        val display = block.world.spawn(block.clone().add(0.5, 0.5, 0.5), ItemDisplay::class.java) {
            it.setItemStack(loot.createBox(BoxType.SUPPLY))
            it.isPersistent = false
            it.isGlowing = true
            it.glowColorOverride = Color.ORANGE
            it.billboard = Display.Billboard.FIXED
            it.teleportDuration = 10
            it.viewRange = 4f
        }
        return Drop(region, block, display)
    }

    private fun announce() {
        val names = drops.joinToString(", ") { "<gold>${it.region.name}</gold>" }
        Bukkit.broadcast(Text.prefix("<yellow>보급품이 도착했습니다! <white>$names</white> <gray>(먼저 여는 사람이 보급 랜덤상자를 얻습니다)"))
        for (player in zones.play.players) {
            player.showTitle(Title.title(
                Text.mm("<gold>보급 도착"),
                Text.mm(drops.joinToString(" <gray>/</gray> ") { "<yellow>${it.region.name}" }),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700)),
            ))
            player.playSound(player.location, Sound.EVENT_RAID_HORN, 0.7f, 1.2f)
        }
    }

    private fun remove(drop: Drop) {
        if (drop.block.block.type == Material.BARRIER) drop.block.block.type = Material.AIR
        drop.display.remove()
        drop.block.chunk.removePluginChunkTicket(plugin)
    }

    private fun clearDrops() {
        drops.forEach(::remove)
        drops.clear()
        saveState()
    }

    // ---------- 획득 ----------

    /** 랜덤상자를 들고 눌러도 상자 개봉보다 보급 획득이 먼저다 (LOW 에서 처리하고 이벤트를 막는다) */
    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val drop = drops.firstOrNull { isSame(it.block, clicked.location) } ?: return
        event.isCancelled = true
        // 보급 상자는 전투 중에도 열 수 있다 (보급지 교전 유도)
        claim(event.player, drop)
    }

    private fun claim(player: Player, drop: Drop) {
        drops.remove(drop)
        remove(drop)
        saveState()
        player.inventory.addItem(loot.createBox(BoxType.SUPPLY)).values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f)
        drop.block.world.spawnParticle(Particle.FIREWORK, drop.block.clone().add(0.5, 1.0, 0.5), 40, 0.4, 0.6, 0.4, 0.1)
        Bukkit.broadcast(Text.prefix("<yellow>${player.name}</yellow>님이 <gold>${drop.region.name}</gold> 보급품을 차지했습니다!"))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (drops.any { isSame(it.block, event.block.location) }) event.isCancelled = true
    }

    private fun isSame(a: Location, b: Location) =
        a.world == b.world && a.blockX == b.blockX && a.blockY == b.blockY && a.blockZ == b.blockZ

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        bars.remove(event.player.uniqueId)
    }

    // ---------- 보스바 ----------

    private fun updateBars() {
        val interval = intervalSeconds().coerceAtLeast(1)
        val left = secondsUntilNext()
        val progress = (left.toFloat() / interval).coerceIn(0f, 1f)
        for (player in Bukkit.getOnlinePlayers()) {
            val bar = bars.getOrPut(player.uniqueId) { BossBar.bossBar(Text.mm(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS) }
            if (!zones.isInPlay(player)) {
                player.hideBossBar(bar)
                continue
            }
            val nearest = drops.minByOrNull { it.block.distanceSquared(player.location) }
            val next = "%d:%02d".format(left / 60, left % 60)
            if (nearest != null) {
                val dist = nearest.block.distance(player.location).roundToInt()
                bar.name(Text.mm("<gold>보급품 <white>${nearest.region.name}</white> ${arrow(player, nearest.block)} <white>${dist}m</white> <gray>| 다음 보급 $next"))
                bar.color(BossBar.Color.RED)
            } else {
                bar.name(Text.mm("<yellow>다음 보급까지 <white>$next"))
                bar.color(BossBar.Color.YELLOW)
            }
            bar.progress(progress)
            player.showBossBar(bar)
        }
    }

    /** 플레이어가 보는 방향 기준으로 목표가 어느 쪽인지 화살표로 */
    private fun arrow(player: Player, target: Location): String {
        val dx = target.x + 0.5 - player.location.x
        val dz = target.z + 0.5 - player.location.z
        val targetYaw = Math.toDegrees(atan2(-dx, dz))
        var rel = (targetYaw - player.location.yaw) % 360
        if (rel > 180) rel -= 360
        if (rel <= -180) rel += 360
        val arrows = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
        val index = (((rel + 360) % 360 + 22.5) / 45).toInt() % 8
        return arrows[index]
    }

    // ---------- 재시작 대비 ----------

    private fun saveState() {
        val yaml = YamlConfiguration()
        yaml.set("blocks", drops.map { "${it.block.world.name} ${it.block.blockX} ${it.block.blockY} ${it.block.blockZ}" })
        yaml.save(stateFile)
    }

    /** 서버가 비정상 종료돼 남은 보급 배리어 블록을 치운다 (ItemDisplay 는 저장되지 않는다) */
    private fun cleanupFromLastRun() {
        if (!stateFile.exists()) return
        val lines = YamlConfiguration.loadConfiguration(stateFile).getStringList("blocks")
        for (line in lines) {
            val p = line.split(" ")
            val world = Bukkit.getWorld(p.getOrNull(0) ?: continue) ?: continue
            val block = world.getBlockAt(p[1].toInt(), p[2].toInt(), p[3].toInt())
            if (block.type == Material.BARRIER) block.type = Material.AIR
        }
        if (lines.isNotEmpty()) plugin.logger.info("지난 실행에서 남은 보급 상자 ${lines.size}개를 정리했습니다.")
        stateFile.delete()
    }
}
