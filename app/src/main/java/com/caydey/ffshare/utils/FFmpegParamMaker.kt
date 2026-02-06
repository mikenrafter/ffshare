package com.caydey.ffshare.utils

import android.net.Uri
import com.arthenica.ffmpegkit.MediaInformation
import timber.log.Timber
import java.util.StringJoiner
import kotlin.math.ceil

class FFmpegParamMaker(val settings: Settings, val utils: Utils) {
    // Returns true if two-pass encoding should be used
    fun shouldUseTwoPass(mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): Boolean {
        return utils.isVideo(outputMediaType) && 
               settings.videoMaxFileSize != 0 && 
               outputMediaType != Utils.MediaType.WEBM // webm doesn't support two-pass with these settings
    }

    fun create(inputFile: Uri, mediaInformation: MediaInformation, mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): String {
        return createPass(inputFile, mediaInformation, mediaType, outputMediaType, passNumber = 0)
    }

    fun createPass(inputFile: Uri, mediaInformation: MediaInformation, mediaType: Utils.MediaType, outputMediaType: Utils.MediaType, passNumber: Int): String {
        // custom params
        if (utils.isImage(outputMediaType) && settings.customImageParams.isNotEmpty()) return settings.customImageParams
        if (utils.isVideo(outputMediaType) && settings.customVideoParams.isNotEmpty()) return settings.customVideoParams
        if (utils.isAudio(outputMediaType) && settings.customAudioParams.isNotEmpty()) return settings.customAudioParams

        val params = StringJoiner(" ")
        val videoFormatParams = StringJoiner(",")

//        val (inputVideoCodec, inputAudioCodec) = getMediaCodecs(mediaInformation)

        // preset, webp does not support this
        if (outputMediaType != Utils.MediaType.WEBP) {
            params.add("-preset ${settings.compressionPreset}")
        }

        if (utils.isAudio(mediaType) || utils.isVideo(mediaType)) {
            // add audio codec
            if (settings.audioCodec != Settings.AudioCodecOpts.DEFAULT) {
                params.add("-c:a ${settings.audioCodec.raw}")
            }
        }

        var videoScaleApplied = false
        // videos and images max resolution
        if (utils.isVideo(mediaType) || utils.isImage(mediaType)) {
            val (resolutionWidth, resolutionHeight) = utils.getMediaResolution(inputFile, mediaType)
            val isPortrait = resolutionHeight > resolutionWidth
            val resolution = if (isPortrait) resolutionWidth else resolutionHeight
            val maxResolution = if (utils.isImage(mediaType)) settings.maxImageResolution else settings.maxVideoResolution
            // only reduce resolution
            if (resolution > maxResolution && maxResolution != 0) {
                videoScaleApplied = true
                var pixelRounding = "-1"
                // H.26x videos require dimensions to be divisible by 2
                if (utils.isVideo(outputMediaType) && settings.videoCodec in setOf(Settings.VideoCodecOpts.H264, Settings.VideoCodecOpts.H265)) {
                    pixelRounding = "-2"
                }
                if (isPortrait) {
                    videoFormatParams.add("scale=$maxResolution:$pixelRounding,setsar=1")
                } else {
                    videoFormatParams.add("scale=$pixelRounding:$maxResolution,setsar=1")
                }
            }
        }

        // video
        if (utils.isVideo(outputMediaType)) { // check outputMediaType not mediaType because conversions
            // pixel format
            videoFormatParams.add("format=yuv420p")

            // video codec
            if (settings.videoCodec != Settings.VideoCodecOpts.DEFAULT) {
                params.add("-c:v ${settings.videoCodec.raw}")
            }

            // H.26x requires dimensions to be divisible by 2, video scaling will account for this if applied
            if (!videoScaleApplied) {
                val stream = mediaInformation.streams[0]
                if (stream?.width?.rem(2) != 0L || stream.height?.rem(2) != 0L) {
                    videoFormatParams.add("crop=trunc(iw/2)*2:trunc(ih/2)*2")
                    // could also use "pad=ceil(iw/2)*2:ceil(ih/2)*2" to add column/row of black pixels
                }
            }

            //  max file size (use two-pass encoding with target bitrate)
            if (settings.videoMaxFileSize != 0) {
                // ffmpeg does not like two-pass params when the output file is webm
                if (outputMediaType != Utils.MediaType.WEBM) {
                    // Calculate target bitrate in kbps.
                    // Using formula: bitrate = target size / duration
                    val targetBitrate = (settings.videoMaxFileSize * 8 / mediaInformation.duration.toFloat()).toInt()
                    Timber.d("Target bitrate for filesize (%dK): %dk", settings.videoMaxFileSize, targetBitrate)

                    // audio can have at most one third of the total bitrate
                    val audioSplit = targetBitrate / 3
                    // round audio bitrate down to 192,128,96,64,32,24
                    val audioBitrate = if (audioSplit > 192) 192 // maximum audio bitrate is 192k
                    else if (audioSplit > 128) 128
                    else if (audioSplit > 96) 96
                    else if (audioSplit > 64) 64
                    else if (audioSplit > 32) 32
                    else 24 // minimum audio bitrate is 24k

                    // set audio bitrate
                    params.add("-b:a ${audioBitrate}k")

                    // set target video bitrate for two-pass encoding
                    val videoBitrate = (targetBitrate - audioBitrate)
                    params.add("-b:v ${videoBitrate}k")
                    
                    // Add pass-specific parameters for two-pass encoding
                    if (passNumber > 0) {
                        params.add("-pass $passNumber")
                    }
                } else {
                    // For webm, use CRF mode as fallback
                    params.add("-crf ${settings.videoCrf}")
                }
            } else {
                // No target file size, use CRF (quality-based encoding)
                params.add("-crf ${settings.videoCrf}")
            }
        }

        if (videoFormatParams.length() > 0) {
            params.add("-vf \"$videoFormatParams\"")
        }

        // jpeg quality
        if (outputMediaType == Utils.MediaType.JPEG || outputMediaType == Utils.MediaType.JPG) {
            params.add("-qscale:v ${settings.jpegQscale}")
        }

        return params.toString()
    }

    private fun getMediaCodecs(mediaInformation: MediaInformation): Pair<Settings.VideoCodecOpts?, Settings.AudioCodecOpts?> {
        var audioCodec: Settings.AudioCodecOpts? = null
        var videoCodec: Settings.VideoCodecOpts? = null
        for (stream in mediaInformation.streams) {
            if (stream.type.lowercase() == "video") {
                videoCodec = Settings.VideoCodecOpts.parseCodec(stream.codec);
            } else if (stream.type.lowercase() == "audio") {
                audioCodec = Settings.AudioCodecOpts.parseCodec((stream.codec))
            }
        }
        return Pair(videoCodec, audioCodec)
    }
}
