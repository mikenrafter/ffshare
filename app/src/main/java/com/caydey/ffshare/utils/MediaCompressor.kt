package com.caydey.ffshare.utils


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.caydey.ffshare.R
import com.caydey.ffshare.utils.logs.Log
import com.caydey.ffshare.utils.logs.LogsDbHelper
import timber.log.Timber
import java.util.*


class MediaCompressor(private val context: Context) {
    private val utils: Utils by lazy { Utils(context) }
    private val settings: Settings by lazy { Settings(context) }
    private val logsDbHelper by lazy { LogsDbHelper(context) }

    private val ffmpegParamMaker = FFmpegParamMaker(settings, utils)

    fun cancelAllOperations() {
        Timber.d("Canceling all ffmpeg operations")
        FFmpegKit.cancel()
    }

    @SuppressLint("SetTextI18n")
    fun compressSingleFile(
        activity: Activity,
        inputFileUri: Uri,
        successHandler: (uri: Uri, inputFileSize: Long, outputFileSize: Long) -> Unit,
        failureHandler: () -> Unit
    ) {
        val txtFfmpegCommand: TextView = activity.findViewById(R.id.txtFfmpegCommand)
        val txtInputFile: TextView = activity.findViewById(R.id.txtInputFile)
        val txtInputFileSize: TextView = activity.findViewById(R.id.txtInputFileSize)
        val txtOutputFile: TextView = activity.findViewById(R.id.txtOutputFile)
        val txtOutputFileSize: TextView = activity.findViewById(R.id.txtOutputFileSize)
        val txtProcessedTime: TextView = activity.findViewById(R.id.txtProcessedTime)
        val txtProcessedTimeTotal: TextView = activity.findViewById(R.id.txtProcessedTimeTotal)
        val txtProcessedPercent: TextView = activity.findViewById(R.id.txtProcessedPercent)
        val processedTableRow: TableRow = activity.findViewById(R.id.processedTableRow)

        // cancel button
        val btnCancel: Button = activity.findViewById(R.id.btnCancel)
        btnCancel.setOnClickListener {
            Toast.makeText(context, context.getString(R.string.ffmpeg_canceled), Toast.LENGTH_LONG).show()
            // cancel all ffmpeg operations
            cancelAllOperations()

            failureHandler() // a cancel is a fail
        }

        val mediaType = utils.getMediaType(inputFileUri)
        if (!utils.isSupportedMediaType(mediaType)) { // not supported show error
            Toast.makeText(context, context.getString(R.string.error_unknown_filetype), Toast.LENGTH_LONG).show()
            failureHandler()
            return
        }

        // don't show progress when compressing images (not possible)
        val showProgress = !utils.isImage(mediaType)
        if (!showProgress) {
            processedTableRow.visibility = View.INVISIBLE
        }

        val inputFileName = utils.getFilenameFromUri(inputFileUri) ?: "unknown"

        // get output file, (random uuid, custom name, original name)
        val (outputFile, outputMediaType) = utils.getCacheOutputFile(inputFileUri, mediaType)

        // get Uri from File, needs to be this way not Uri.fromFile(...) to go through security
        val outputFileUri = FileProvider.getUriForFile(context, context.applicationContext.packageName+".fileprovider", outputFile)

        // need to create new saf param as they are one-use
        val mediaInformation = FFprobeKit.getMediaInformation(FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)).mediaInformation

        if (mediaInformation == null) {
            Timber.d("Unable to get media information, throwing error")
            Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show()
            failureHandler()
            return
        }

        val inputFileSize = mediaInformation.size.toLong() // get input file size

        var duration = 0 // default duration for image
        if (showProgress) {
            // invalid video file if ffprobe cant parse duration and size
            if (mediaInformation.duration == null || mediaInformation.size == null) {
                Timber.d("Unable to get size & duration for media, throwing error")
                Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show()
                failureHandler()
                return
            }
            duration = (mediaInformation.duration.toFloat() * 1_000).toInt()
        }

        // Check if two-pass encoding is needed
        val useTwoPass = ffmpegParamMaker.shouldUseTwoPass(mediaType, outputMediaType)
        
        if (useTwoPass) {
            Timber.d("Using two-pass encoding for target file size")
            executeTwoPassEncoding(
                activity, inputFileUri, mediaInformation, mediaType, outputMediaType,
                inputFileName, outputFile, outputFileUri, inputFileSize, duration,
                showProgress, successHandler, failureHandler
            )
        } else {
            executeSinglePassEncoding(
                activity, inputFileUri, mediaInformation, mediaType, outputMediaType,
                inputFileName, outputFile, outputFileUri, inputFileSize, duration,
                showProgress, successHandler, failureHandler
            )
        }
    }

    private fun executeSinglePassEncoding(
        activity: Activity,
        inputFileUri: Uri,
        mediaInformation: MediaInformation,
        mediaType: Utils.MediaType,
        outputMediaType: Utils.MediaType,
        inputFileName: String,
        outputFile: java.io.File,
        outputFileUri: Uri,
        inputFileSize: Long,
        duration: Int,
        showProgress: Boolean,
        successHandler: (uri: Uri, inputFileSize: Long, outputFileSize: Long) -> Unit,
        failureHandler: () -> Unit
    ) {
        val txtFfmpegCommand: TextView = activity.findViewById(R.id.txtFfmpegCommand)
        val txtInputFile: TextView = activity.findViewById(R.id.txtInputFile)
        val txtInputFileSize: TextView = activity.findViewById(R.id.txtInputFileSize)
        val txtOutputFile: TextView = activity.findViewById(R.id.txtOutputFile)
        val txtOutputFileSize: TextView = activity.findViewById(R.id.txtOutputFileSize)
        val txtProcessedTime: TextView = activity.findViewById(R.id.txtProcessedTime)
        val txtProcessedTimeTotal: TextView = activity.findViewById(R.id.txtProcessedTimeTotal)
        val txtProcessedPercent: TextView = activity.findViewById(R.id.txtProcessedPercent)

        val params = ffmpegParamMaker.create(inputFileUri, mediaInformation, mediaType, outputMediaType)
        val inputSaf: String = FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)
        val outputSaf: String = FFmpegKitConfig.getSafParameterForWrite(context, outputFileUri)
        val command = "-y -i $inputSaf $params $outputSaf"
        val prettyCommand = "ffmpeg -y -i $inputFileName $params ${outputFile.name}"

        // set TextViews
        Handler(Looper.getMainLooper()).post {
            txtFfmpegCommand.text = prettyCommand
            txtInputFile.text = inputFileName
            txtInputFileSize.text = utils.bytesToHuman(inputFileSize)
            txtOutputFile.text = outputFile.name
            txtOutputFileSize.text = utils.bytesToHuman(0)
            txtProcessedTime.text = utils.millisToMicrowaveTime(0)
            txtProcessedTimeTotal.text = utils.millisToMicrowaveTime(duration)
            txtProcessedPercent.text = context.getString(R.string.format_percentage, 0.0f)
        }

        Timber.d("Executing ffmpeg command: 'ffmpeg %s'", command)
        FFmpegKit.executeAsync(command, { session ->
            // completed
            if (!session.returnCode.isValueSuccess) { // failed
                if (!session.returnCode.isValueCancel) { // failure was not caused by a cancel
                    Timber.d("ffmpeg command failed")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.ffmpeg_error), Toast.LENGTH_LONG).show()
                    }
                    // save log

                    logsDbHelper.addLog(Log(
                        prettyCommand,
                        inputFileName,
                        outputFile.name,
                        false,
                        session.output,
                        inputFileSize,
                        -1
                    ))
                    failureHandler()
                }
            } else { // success
                Timber.d("ffmpeg command executed successfully")
                if (settings.copyExifTags && ExifTools.isValidType(mediaType)) {
                    Timber.d("copying exif tags")
                    ExifTools.copyExif(context.contentResolver.openInputStream(inputFileUri)!!, outputFile)
                }
                val outputFileCurrentSize = outputFile.length()
                // """Only the original thread that created a view hierarchy can touch its views."""
                Handler(Looper.getMainLooper()).post {
                    // update TextViews to their final values 97.8% -> 100.0%
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, 100.0f)
                    txtProcessedTime.text = utils.millisToMicrowaveTime(duration)
                    if (outputFileCurrentSize > 0) {
                        txtOutputFileSize.text = utils.bytesToHuman(outputFileCurrentSize)
                    }
                }

                logsDbHelper.addLog(Log(
                    prettyCommand,
                    inputFileName,
                    outputFile.name,
                    true,
                    session.output,
                    inputFileSize,
                    outputFileCurrentSize
                ))
                // callback
                successHandler(outputFileUri, inputFileSize, outputFileCurrentSize)
            }
        }, { /* logs */ }, { statistics ->
            // update TextViews with stats
            Handler(Looper.getMainLooper()).post {
                if (showProgress) { // only show time processed if video
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, (statistics.time.toFloat() / duration) * 100)
                    txtProcessedTime.text = utils.millisToMicrowaveTime(statistics.time.toInt())
                }
                txtOutputFileSize.text = utils.bytesToHuman(statistics.size)
            }
        })
    }

    private fun executeTwoPassEncoding(
        activity: Activity,
        inputFileUri: Uri,
        mediaInformation: MediaInformation,
        mediaType: Utils.MediaType,
        outputMediaType: Utils.MediaType,
        inputFileName: String,
        outputFile: java.io.File,
        outputFileUri: Uri,
        inputFileSize: Long,
        duration: Int,
        showProgress: Boolean,
        successHandler: (uri: Uri, inputFileSize: Long, outputFileSize: Long) -> Unit,
        failureHandler: () -> Unit
    ) {
        val txtFfmpegCommand: TextView = activity.findViewById(R.id.txtFfmpegCommand)
        val txtInputFile: TextView = activity.findViewById(R.id.txtInputFile)
        val txtInputFileSize: TextView = activity.findViewById(R.id.txtInputFileSize)
        val txtOutputFile: TextView = activity.findViewById(R.id.txtOutputFile)
        val txtOutputFileSize: TextView = activity.findViewById(R.id.txtOutputFileSize)
        val txtProcessedTime: TextView = activity.findViewById(R.id.txtProcessedTime)
        val txtProcessedTimeTotal: TextView = activity.findViewById(R.id.txtProcessedTimeTotal)
        val txtProcessedPercent: TextView = activity.findViewById(R.id.txtProcessedPercent)

        val inputSaf: String = FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)
        val outputSaf: String = FFmpegKitConfig.getSafParameterForWrite(context, outputFileUri)

        // Pass 1: Analysis pass
        val paramsPass1 = ffmpegParamMaker.createPass(inputFileUri, mediaInformation, mediaType, outputMediaType, passNumber = 1)
        val commandPass1 = "-y -i $inputSaf $paramsPass1 -f ${getOutputFormat(outputMediaType)} /dev/null"
        val prettyCommandPass1 = "ffmpeg -y -i $inputFileName $paramsPass1 -f ${getOutputFormat(outputMediaType)} /dev/null"

        // Pass 2: Encoding pass
        val paramsPass2 = ffmpegParamMaker.createPass(inputFileUri, mediaInformation, mediaType, outputMediaType, passNumber = 2)
        val commandPass2 = "-y -i $inputSaf $paramsPass2 $outputSaf"
        val prettyCommandPass2 = "ffmpeg -y -i $inputFileName $paramsPass2 ${outputFile.name}"

        val prettyCommandBoth = "Pass 1: $prettyCommandPass1\nPass 2: $prettyCommandPass2"

        // set TextViews
        Handler(Looper.getMainLooper()).post {
            txtFfmpegCommand.text = prettyCommandBoth
            txtInputFile.text = inputFileName
            txtInputFileSize.text = utils.bytesToHuman(inputFileSize)
            txtOutputFile.text = outputFile.name
            txtOutputFileSize.text = utils.bytesToHuman(0)
            txtProcessedTime.text = utils.millisToMicrowaveTime(0)
            txtProcessedTimeTotal.text = utils.millisToMicrowaveTime(duration)
            txtProcessedPercent.text = context.getString(R.string.format_percentage, 0.0f)
        }

        Timber.d("Executing two-pass encoding")
        Timber.d("Pass 1: 'ffmpeg %s'", commandPass1)

        // Execute Pass 1
        FFmpegKit.executeAsync(commandPass1, { sessionPass1 ->
            // Pass 1 completed
            if (!sessionPass1.returnCode.isValueSuccess) { // failed
                if (!sessionPass1.returnCode.isValueCancel) { // failure was not caused by a cancel
                    Timber.d("ffmpeg pass 1 failed")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.ffmpeg_error), Toast.LENGTH_LONG).show()
                    }
                    logsDbHelper.addLog(Log(
                        prettyCommandBoth,
                        inputFileName,
                        outputFile.name,
                        false,
                        "Pass 1 failed:\n${sessionPass1.output}",
                        inputFileSize,
                        -1
                    ))
                    failureHandler()
                }
            } else { // Pass 1 success, start Pass 2
                Timber.d("Pass 1 completed successfully, starting Pass 2")
                Timber.d("Pass 2: 'ffmpeg %s'", commandPass2)
                
                // Update UI to show we're in pass 2
                Handler(Looper.getMainLooper()).post {
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, 50.0f)
                }

                // Execute Pass 2
                FFmpegKit.executeAsync(commandPass2, { sessionPass2 ->
                    // Pass 2 completed
                    if (!sessionPass2.returnCode.isValueSuccess) { // failed
                        if (!sessionPass2.returnCode.isValueCancel) { // failure was not caused by a cancel
                            Timber.d("ffmpeg pass 2 failed")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, context.getString(R.string.ffmpeg_error), Toast.LENGTH_LONG).show()
                            }
                            logsDbHelper.addLog(Log(
                                prettyCommandBoth,
                                inputFileName,
                                outputFile.name,
                                false,
                                "Pass 2 failed:\n${sessionPass2.output}",
                                inputFileSize,
                                -1
                            ))
                            failureHandler()
                        }
                    } else { // Pass 2 success
                        Timber.d("ffmpeg two-pass encoding completed successfully")
                        if (settings.copyExifTags && ExifTools.isValidType(mediaType)) {
                            Timber.d("copying exif tags")
                            ExifTools.copyExif(context.contentResolver.openInputStream(inputFileUri)!!, outputFile)
                        }
                        val outputFileCurrentSize = outputFile.length()
                        Handler(Looper.getMainLooper()).post {
                            txtProcessedPercent.text = context.getString(R.string.format_percentage, 100.0f)
                            txtProcessedTime.text = utils.millisToMicrowaveTime(duration)
                            if (outputFileCurrentSize > 0) {
                                txtOutputFileSize.text = utils.bytesToHuman(outputFileCurrentSize)
                            }
                        }

                        logsDbHelper.addLog(Log(
                            prettyCommandBoth,
                            inputFileName,
                            outputFile.name,
                            true,
                            "Pass 1:\n${sessionPass1.output}\n\nPass 2:\n${sessionPass2.output}",
                            inputFileSize,
                            outputFileCurrentSize
                        ))
                        successHandler(outputFileUri, inputFileSize, outputFileCurrentSize)
                    }
                }, { /* logs */ }, { statistics ->
                    // update TextViews with stats for pass 2
                    Handler(Looper.getMainLooper()).post {
                        if (showProgress && duration > 0) {
                            // Pass 2 progress: 50% + (current_progress / 2)
                            val pass2Progress = 50.0f + (statistics.time.toFloat() / duration) * 50
                            txtProcessedPercent.text = context.getString(R.string.format_percentage, pass2Progress)
                            txtProcessedTime.text = utils.millisToMicrowaveTime(statistics.time.toInt())
                        }
                        txtOutputFileSize.text = utils.bytesToHuman(statistics.size)
                    }
                })
            }
        }, { /* logs */ }, { statistics ->
            // update TextViews with stats for pass 1
            Handler(Looper.getMainLooper()).post {
                if (showProgress && duration > 0) {
                    // Pass 1 progress: 0% to 50%
                    val pass1Progress = (statistics.time.toFloat() / duration) * 50
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, pass1Progress)
                    txtProcessedTime.text = utils.millisToMicrowaveTime(statistics.time.toInt())
                }
            }
        })
    }

    private fun getOutputFormat(mediaType: Utils.MediaType): String {
        return when (mediaType) {
            Utils.MediaType.MP4 -> "mp4"
            Utils.MediaType.WEBM -> "webm"
            Utils.MediaType.MKV -> "matroska"
            Utils.MediaType.AVI -> "avi"
            else -> "mp4" // default to mp4
        }
    }

    fun compressFiles(activity: Activity, inputFilesUri: ArrayList<Uri>, callback: (uris: ArrayList<Uri>) -> Unit) {
        val txtCommandNumber: TextView = activity.findViewById(R.id.txtCommandNumber)

        val inputFilesCount = inputFilesUri.size

        val compressedFiles = ArrayList<Uri>()

        var totalInputFileSize = 0L
        var totalOutputFileSize = 0L

        // since we are working with callbacks a simple for loop wont work
        lateinit var iteratorFunction: (Int, Boolean) -> Unit
        iteratorFunction = fun(i, error) {
            // base case
            if (i < inputFilesCount) {
                Timber.d("Processing %d of %d files", i+1, inputFilesCount)

                if (inputFilesCount > 1) { // show "1 of N" label if N > 1
                    Handler(Looper.getMainLooper()).post {
                        txtCommandNumber.text = context.getString(R.string.command_x_of_y, i+1, inputFilesCount)
                    }
                }

                compressSingleFile(activity, inputFilesUri[i], failureHandler = {
                    // if 1 file fails don't add it to compressedFiles array
                    iteratorFunction(i+1, true) // call to self with error flag true
                }, successHandler = { uri, inputFileSize, outputFileSize ->
                    totalInputFileSize += inputFileSize
                    totalOutputFileSize += outputFileSize
                    compressedFiles.add(uri)
                    iteratorFunction(i+1, false) // call to self with error flag false
                })
            }
            if (i >= inputFilesCount || error) { // end of iterations
                // show compression percentage as toast message if there was no error
                if (settings.showStatusMessages && !error) {
                    val totalOutputFileSizeHuman = utils.bytesToHuman(totalOutputFileSize)
                    val compressionPercentage = (1 - (totalOutputFileSize.toDouble() / totalInputFileSize)) * 100
                    val toastMessage = context.getString(R.string.media_reduction_message, totalOutputFileSizeHuman, compressionPercentage)
                    Handler(Looper.getMainLooper()).post {
                        Timber.d("Showing compression size toast message")
                        Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
                    }
                }
                callback(compressedFiles)
            }
        }
        iteratorFunction(0, false) // start iterations
    }

}
