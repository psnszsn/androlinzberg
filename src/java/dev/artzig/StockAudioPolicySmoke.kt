package dev.artzig

import android.audio.policy.configuration.V7_0.AudioChannelMask
import android.audio.policy.configuration.V7_0.AudioInOutFlag
import android.audio.policy.configuration.V7_0.AudioUsage
import android.audio.policy.configuration.V7_0.MixType
import android.audio.policy.configuration.V7_0.Role
import android.audio.policy.configuration.V7_0.XmlParser

/** Exercise the pinned xsdc output through the audio policy XML contract. */
object StockAudioPolicySmoke {
    fun run() {
        val xml = """
            <audioPolicyConfiguration version="7.0">
              <globalConfiguration speaker_drc_enabled="true"/>
              <modules><module name="primary" halVersion="3.0">
                <attachedDevices><item>Speaker</item></attachedDevices>
                <defaultOutputDevice>Speaker</defaultOutputDevice>
                <mixPorts><mixPort name="primary output" role="source"
                    flags="AUDIO_OUTPUT_FLAG_PRIMARY AUDIO_OUTPUT_FLAG_FAST"
                    preferredUsage="AUDIO_USAGE_MEDIA AUDIO_USAGE_ASSISTANT">
                  <profile format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="44100 48000"
                      channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                </mixPort></mixPorts>
                <devicePorts><devicePort tagName="Speaker" type="AUDIO_DEVICE_OUT_SPEAKER"
                    role="sink" address=""/></devicePorts>
                <routes><route type="mix" sink="Speaker" sources="primary output"/></routes>
              </module></modules>
              <futureExtension><nested><ignored/></nested></futureExtension>
            </audioPolicyConfiguration>
        """.trimIndent()
        val config = XmlParser.read(xml.byteInputStream())!!
        check(config.version!!.rawName == "7.0")
        check(config.globalConfiguration!!.getSpeaker_drc_enabled())
        check(!config.globalConfiguration!!.getCall_screen_mode_supported())
        val module = config.modules!!.single().module!!.single()
        check(module.name == "primary" && module.halVersion!!.rawName == "3.0")
        check(module.attachedDevices!!.item == listOf("Speaker"))
        check(module.defaultOutputDevice == "Speaker")
        val port = module.mixPorts!!.mixPort!!.single()
        check(port.name == "primary output" && port.role == Role.source)
        check(port.flags == listOf(AudioInOutFlag.AUDIO_OUTPUT_FLAG_PRIMARY,
            AudioInOutFlag.AUDIO_OUTPUT_FLAG_FAST))
        check(port.preferredUsage == listOf(AudioUsage.AUDIO_USAGE_MEDIA,
            AudioUsage.AUDIO_USAGE_ASSISTANT))
        val profile = port.profile!!.single()
        check(profile.format == "AUDIO_FORMAT_PCM_16_BIT")
        check(profile.samplingRates!!.map { it.toInt() } == listOf(44100, 48000))
        check(profile.channelMasks == listOf(AudioChannelMask.AUDIO_CHANNEL_OUT_STEREO))
        val device = module.devicePorts!!.devicePort!!.single()
        check(device.tagName == "Speaker" && device.role == Role.sink)
        check(device.type == "AUDIO_DEVICE_OUT_SPEAKER" && device.address == "")
        val route = module.routes!!.route!!.single()
        check(route.type == MixType.mix && route.sink == "Speaker")
        check(route.sources == "primary output")
        check(AudioUsage.AUDIO_USAGE_CALL_ASSISTANT.toString() == "AUDIO_USAGE_CALL_ASSISTANT")
        check(AudioUsage.values().all { it.toString() == it.rawName })
        check(XmlParser.read("<unrecognized/>".byteInputStream()) == null)
        check(runCatching {
            XmlParser.read(xml.replace("version=\"7.0\"", "version=\"invalid\"").byteInputStream())
        }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching {
            XmlParser.read(xml.replace("AUDIO_USAGE_MEDIA", "INVALID_USAGE").byteInputStream())
        }.exceptionOrNull() is IllegalArgumentException)
    }
}
