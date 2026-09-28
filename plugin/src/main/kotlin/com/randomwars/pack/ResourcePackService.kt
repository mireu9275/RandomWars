package com.randomwars.pack

import com.randomwars.Text
import com.sun.net.httpserver.HttpServer
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.plugin.java.JavaPlugin
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/**
 * 플러그인 jar 안의 pack.zip 을 내장 HTTP 서버로 내려주고, 접속한 플레이어에게 필수로 적용한다.
 * external-url 이 설정되면 내장 서버 대신 그 주소를 쓴다 (같은 zip 을 올렸다고 가정하고 해시를 계산).
 */
class ResourcePackService(private val plugin: JavaPlugin) : Listener {
    private var http: HttpServer? = null
    private var request: ResourcePackRequest? = null

    fun start(config: ConfigurationSection?) {
        stop()
        if (config == null || !config.getBoolean("enabled", true)) return

        val bytes = plugin.getResource("pack.zip")?.use { it.readBytes() } ?: run {
            plugin.logger.warning("플러그인 안에 pack.zip 이 없어 리소스팩을 보내지 않습니다.")
            return
        }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        val url = config.getString("external-url").orEmpty().ifBlank {
            val port = config.getInt("port", 8163)
            val server = HttpServer.create(InetSocketAddress(port), 0)
            server.createContext("/") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/zip")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            http = server
            // 파일명에 해시를 넣어 클라이언트 캐시가 옛 팩을 쓰지 않게 한다.
            "http://${config.getString("public-host", "localhost")}:$port/randomwars-${sha1.take(12)}.zip"
        }

        val info = ResourcePackInfo.resourcePackInfo(UUID.nameUUIDFromBytes("randomwars".toByteArray()), URI.create(url), sha1)
        request = ResourcePackRequest.resourcePackRequest()
            .packs(info)
            .required(config.getBoolean("required", true))
            .prompt(Text.mm(config.getString("prompt", "")!!))
            .replace(true)
            .build()
        plugin.logger.info("리소스팩 준비: $url (sha1 $sha1, ${bytes.size} bytes)")
    }

    fun stop() {
        http?.stop(0)
        http = null
        request = null
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        request?.let { event.player.sendResourcePacks(it) }
    }

    @EventHandler
    fun onStatus(event: PlayerResourcePackStatusEvent) {
        if (event.status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            plugin.logger.warning("${event.player.name}: 리소스팩 다운로드 실패. public-host/port 설정과 방화벽을 확인하세요.")
        }
    }
}
