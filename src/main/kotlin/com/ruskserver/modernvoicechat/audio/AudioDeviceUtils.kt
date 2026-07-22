package com.ruskserver.modernvoicechat.audio

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

object AudioDeviceUtils {
    const val DEFAULT_DEVICE = "Default"

    /**
     * システムで利用可能なマイク（録音/入力）デバイス名のリストを取得。
     */
    fun getAvailableMicrophones(): List<String> {
        val devices = mutableListOf(DEFAULT_DEVICE)
        val mixerInfos = AudioSystem.getMixerInfo()

        for (info in mixerInfos) {
            try {
                val mixer = AudioSystem.getMixer(info)
                val targetInfo = DataLine.Info(TargetDataLine::class.java, null)
                if (mixer.isLineSupported(targetInfo)) {
                    val name = info.name.trim()
                    if (name.isNotEmpty() && !devices.contains(name)) {
                        devices.add(name)
                    }
                }
            } catch (e: Throwable) {}
        }
        return devices
    }

    /**
     * システムで利用可能なスピーカー（再生/出力）デバイス名のリストを取得。
     */
    fun getAvailableSpeakers(): List<String> {
        val devices = mutableListOf(DEFAULT_DEVICE)
        val mixerInfos = AudioSystem.getMixerInfo()

        for (info in mixerInfos) {
            try {
                val mixer = AudioSystem.getMixer(info)
                val sourceInfo = DataLine.Info(SourceDataLine::class.java, null)
                if (mixer.isLineSupported(sourceInfo)) {
                    val name = info.name.trim()
                    if (name.isNotEmpty() && !devices.contains(name)) {
                        devices.add(name)
                    }
                }
            } catch (e: Throwable) {}
        }
        return devices
    }

    /**
     * デバイス名に対応する Mixer.Info を検索する。
     */
    fun getMixerInfoByName(deviceName: String): Mixer.Info? {
        if (deviceName == DEFAULT_DEVICE) return null
        val mixerInfos = AudioSystem.getMixerInfo()
        return mixerInfos.firstOrNull { it.name.trim() == deviceName }
    }
}
