package io.github.beal1031.fin2go.data.playback

import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

/**
 * Tells the server what this phone can play. The server compares a file with it and either sends the
 * original file (direct play) or converts it (transcoding) into an HLS stream with H.264/HEVC and AAC.
 * Video codecs come from the decoders of the phone, audio codecs also from the FFmpeg module.
 */
@OptIn(UnstableApi::class)
fun deviceProfile(): DeviceProfile {
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
    fun hasDecoder(mime: String) = decoders.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    val videoCodecs = VIDEO_CODECS.filterKeys(::hasDecoder).values.toList()
    val ffmpeg = FfmpegLibrary.isAvailable()
    val audioCodecs = AUDIO_CODECS
        .filterKeys { mime -> hasDecoder(mime) || (ffmpeg && FfmpegLibrary.supportsFormat(mime)) }
        .values.flatten().distinct()

    return buildDeviceProfile {
        name = "Fin2Go"
        maxStreamingBitrate = MAX_BITRATE
        maxStaticBitrate = MAX_BITRATE

        directPlayProfile {
            type = DlnaProfileType.VIDEO
            container(*DIRECT_PLAY_CONTAINERS)
            videoCodec(*videoCodecs.toTypedArray())
            audioCodec(*audioCodecs.toTypedArray())
        }

        transcodingProfile {
            type = DlnaProfileType.VIDEO
            context = EncodingContext.STREAMING
            protocol = MediaStreamProtocol.HLS
            container = "ts"
            videoCodec(*listOf("hevc", "h264").filter { it in videoCodecs }.toTypedArray())
            audioCodec(*listOf("aac", "mp3", "ac3", "eac3").filter { it in audioCodecs }.toTypedArray())
            copyTimestamps = false
            minSegments = 1
            breakOnNonKeyFrames = false
        }

        // Subtitles inside the file are read by the player itself, so they never force a transcode.
        EMBEDDED_SUBTITLES.forEach { subtitleProfile(it, SubtitleDeliveryMethod.EMBED) }
        // Text subtitles in a file of their own are loaded next to the video.
        EXTERNAL_SUBTITLES.forEach { subtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL) }
    }
}

/** 120 Mbit/s: enough for 4K files, so the server does not lower the quality on its own. */
private const val MAX_BITRATE = 120_000_000

private val DIRECT_PLAY_CONTAINERS = arrayOf("mkv", "matroska", "webm", "mp4", "m4v", "mov", "ts", "mpegts")

private val EMBEDDED_SUBTITLES = listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "pgs", "pgssub", "dvdsub")

private val EXTERNAL_SUBTITLES = listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")

/** Android type of a decoder and the name the server uses for it. */
private val VIDEO_CODECS = mapOf(
    MimeTypes.VIDEO_H264 to "h264",
    MimeTypes.VIDEO_H265 to "hevc",
    MimeTypes.VIDEO_AV1 to "av1",
    MimeTypes.VIDEO_VP9 to "vp9",
    MimeTypes.VIDEO_VP8 to "vp8",
    MimeTypes.VIDEO_MPEG2 to "mpeg2video",
)

private val AUDIO_CODECS = mapOf(
    MimeTypes.AUDIO_AAC to listOf("aac"),
    MimeTypes.AUDIO_MPEG to listOf("mp3"),
    MimeTypes.AUDIO_OPUS to listOf("opus"),
    MimeTypes.AUDIO_VORBIS to listOf("vorbis"),
    MimeTypes.AUDIO_FLAC to listOf("flac"),
    MimeTypes.AUDIO_ALAC to listOf("alac"),
    MimeTypes.AUDIO_AC3 to listOf("ac3"),
    MimeTypes.AUDIO_E_AC3 to listOf("eac3"),
    MimeTypes.AUDIO_DTS to listOf("dts", "dca"),
    MimeTypes.AUDIO_TRUEHD to listOf("truehd"),
)
