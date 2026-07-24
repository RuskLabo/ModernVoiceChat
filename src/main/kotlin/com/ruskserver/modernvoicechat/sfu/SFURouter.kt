package com.ruskserver.modernvoicechat.sfu

import com.ruskserver.modernvoicechat.config.ServerConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PlayerPosition(
    val x: Double,
    val y: Double,
    val z: Double,
    val dimension: String
)

/**
 * サーバー側でプレイヤー位置（可聴範囲）に基づき、
 * 音声ストリームをどの受信者に転送するかを決定する SFU (Selective Forwarding Unit) ルーター。
 */
class SFURouter(
    var maxDistance: Double = try { ServerConfig.VOICE_RANGE.get() } catch (e: Throwable) { 24.0 }
) {
    private val playerPositions = ConcurrentHashMap<UUID, PlayerPosition>()
    private val directLinks = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val isolatedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val voiceGroups = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    fun updatePosition(playerUuid: UUID, position: PlayerPosition) {
        playerPositions[playerUuid] = position
    }

    fun getPosition(playerUuid: UUID): PlayerPosition? = playerPositions[playerUuid]

    fun removePlayer(playerUuid: UUID) {
        playerPositions.remove(playerUuid)
        directLinks.remove(playerUuid)
        isolatedPlayers.remove(playerUuid)
        for ((_, members) in voiceGroups) {
            members.remove(playerUuid)
        }
    }

    // 1対1 ダイレクト音声リンク
    fun addDirectLink(playerA: UUID, playerB: UUID, bidirectional: Boolean = true, isolateProximity: Boolean = false) {
        directLinks.computeIfAbsent(playerA) { ConcurrentHashMap.newKeySet() }.add(playerB)
        if (bidirectional) {
            directLinks.computeIfAbsent(playerB) { ConcurrentHashMap.newKeySet() }.add(playerA)
        }
        if (isolateProximity) {
            isolatedPlayers.add(playerA)
            if (bidirectional) isolatedPlayers.add(playerB)
        }
    }

    fun removeDirectLink(playerA: UUID, playerB: UUID, bidirectional: Boolean = true) {
        directLinks[playerA]?.remove(playerB)
        if (bidirectional) {
            directLinks[playerB]?.remove(playerA)
        }
        isolatedPlayers.remove(playerA)
        isolatedPlayers.remove(playerB)
    }

    // ボイスグループ
    fun createVoiceGroup(groupId: UUID = UUID.randomUUID()): UUID {
        voiceGroups.putIfAbsent(groupId, ConcurrentHashMap.newKeySet())
        return groupId
    }

    fun addPlayerToGroup(groupId: UUID, playerUuid: UUID) {
        voiceGroups.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(playerUuid)
    }

    fun removePlayerFromGroup(groupId: UUID, playerUuid: UUID) {
        voiceGroups[groupId]?.remove(playerUuid)
    }

    fun disbandVoiceGroup(groupId: UUID) {
        voiceGroups.remove(groupId)
    }

    fun getRecipientsForSender(senderUuid: UUID): List<UUID> {
        val recipients = mutableSetOf<UUID>()

        // 1. ダイレクトリンク受給者
        directLinks[senderUuid]?.let { recipients.addAll(it) }

        // 2. ボイスグループ受給者
        for ((_, members) in voiceGroups) {
            if (members.contains(senderUuid)) {
                recipients.addAll(members.filter { it != senderUuid })
            }
        }

        // 3. 通常の空間近接ボイス (隔離されている場合はスキップ)
        if (!isolatedPlayers.contains(senderUuid)) {
            val senderPos = playerPositions[senderUuid]
            if (senderPos != null) {
                val maxDistanceSq = maxDistance * maxDistance
                for ((uuid, pos) in playerPositions) {
                    if (uuid == senderUuid) continue
                    if (pos.dimension != senderPos.dimension) continue

                    val dx = pos.x - senderPos.x
                    val dy = pos.y - senderPos.y
                    val dz = pos.z - senderPos.z
                    val distSq = dx * dx + dy * dy + dz * dz

                    if (distSq <= maxDistanceSq) {
                        recipients.add(uuid)
                    }
                }
            }
        }

        return recipients.toList()
    }

    fun getDistance(senderUuid: UUID, receiverUuid: UUID): Double? {
        val posA = playerPositions[senderUuid] ?: return null
        val posB = playerPositions[receiverUuid] ?: return null
        if (posA.dimension != posB.dimension) return null

        val dx = posA.x - posB.x
        val dy = posA.y - posB.y
        val dz = posA.z - posB.z
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * 無線の距離に応じた電波品質 Q ∈ [0.0, 1.0] を算出します。
     *  - 0m ～ 700m: 1.0f (100% 鮮明でクリアな通信)
     *  - 700m ～ 1000m: 1.0f -> 0.0f に減衰 (ノイズ・ドロップアウト発生)
     *  - 1000m 以遠: 0.0f (遮断)
     */
    fun calculateRadioQuality(
        distance: Double,
        clearRange: Double = try { ServerConfig.RADIO_CLEAR_RANGE.get() } catch (e: Throwable) { 700.0 },
        maxRange: Double = try { ServerConfig.RADIO_MAX_RANGE.get() } catch (e: Throwable) { 1000.0 }
    ): Float {
        if (distance <= clearRange) return 1.0f
        if (distance >= maxRange) return 0.0f
        val rangeDelta = maxRange - clearRange
        val currentDelta = maxRange - distance
        return (currentDelta / rangeDelta).toFloat().coerceIn(0.0f, 1.0f)
    }
}
