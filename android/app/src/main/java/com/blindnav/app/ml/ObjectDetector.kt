package com.blindnav.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ObjectDetector(
    private val context: Context,
    private val modelFileName: String = "yolo11n.tflite",
    private val labelFileName: String = "labels.txt",
    private val confidenceThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.50f
) {

    private var interpreter: Interpreter? = null
    private val labels = mutableListOf<String>()
    private var gpuDelegate: GpuDelegate? = null

    private val inputTensorWidth = 640
    private val inputTensorHeight = 640

    init {
        setupDetector()
    }

    private fun setupDetector() {
        try {
            labels.clear()
            labels.addAll(FileUtil.loadLabels(context, labelFileName))

            val options = Interpreter.Options()
            val compatList = CompatibilityList()

            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                gpuDelegate = GpuDelegate(delegateOptions)
                options.addDelegate(gpuDelegate)
            } else {
                options.setNumThreads(4)
            }

            val modelBuffer = FileUtil.loadMappedFile(context, modelFileName)
            interpreter = Interpreter(modelBuffer, options)

        } catch (e: IOException) {
            e.printStackTrace()
            // Fallback to CPU if GPU delegate fails
            try {
                val options = Interpreter.Options().apply { setNumThreads(4) }
                val modelBuffer = FileUtil.loadMappedFile(context, modelFileName)
                interpreter = Interpreter(modelBuffer, options)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    fun detect(bitmap: Bitmap): List<DetectedObject> {
        val tflite = interpreter ?: return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputTensorWidth, inputTensorHeight, true)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

        // YOLO11 output tensor shape: [1, 84, 8400] (4 box coords + 80 class scores)
        val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } }

        tflite.run(inputBuffer, outputBuffer)

        return parseYoloOutput(outputBuffer[0])
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputTensorWidth * inputTensorHeight * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputTensorWidth * inputTensorHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixelIndex = 0
        for (i in 0 until inputTensorWidth) {
            for (j in 0 until inputTensorHeight) {
                val value = intValues[pixelIndex++]
                byteBuffer.putFloat(((value shr 16 and 0xFF) / 255.0f))
                byteBuffer.putFloat(((value shr 8 and 0xFF) / 255.0f))
                byteBuffer.putFloat(((value and 0xFF) / 255.0f))
            }
        }
        return byteBuffer
    }

    private fun parseYoloOutput(output: Array<FloatArray>): List<DetectedObject> {
        val candidates = mutableListOf<DetectedObject>()
        val numChannels = output.size // 84
        val numAnchors = output[0].size // 8400

        for (a in 0 until numAnchors) {
            var maxScore = 0f
            var maxClassId = -1

            for (c in 4 until numChannels) {
                val score = output[c][a]
                if (score > maxScore) {
                    maxScore = score
                    maxClassId = c - 4
                }
            }

            if (maxScore >= confidenceThreshold && maxClassId in labels.indices) {
                val cx = output[0][a] / inputTensorWidth
                val cy = output[1][a] / inputTensorHeight
                val w = output[2][a] / inputTensorWidth
                val h = output[3][a] / inputTensorHeight

                val left = (cx - w / 2.0f).coerceIn(0.0f, 1.0f)
                val top = (cy - h / 2.0f).coerceIn(0.0f, 1.0f)
                val right = (cx + w / 2.0f).coerceIn(0.0f, 1.0f)
                val bottom = (cy + h / 2.0f).coerceIn(0.0f, 1.0f)

                val rect = RectF(left, top, right, bottom)
                val label = labels[maxClassId]
                val distance = DistanceEstimator.estimateDistance(label, rect)
                val position = DistanceEstimator.determinePosition(rect)
                val threatLevel = DistanceEstimator.determineThreatLevel(distance, label)

                candidates.add(
                    DetectedObject(
                        classId = maxClassId,
                        label = label,
                        confidence = maxScore,
                        boundingBox = rect,
                        distanceMeters = distance,
                        position = position,
                        threatLevel = threatLevel
                    )
                )
            }
        }

        return applyNms(candidates)
    }

    private fun applyNms(objects: List<DetectedObject>): List<DetectedObject> {
        val sorted = objects.sortedByDescending { it.confidence }
        val selected = mutableListOf<DetectedObject>()
        val active = BooleanArray(sorted.size) { true }

        for (i in sorted.indices) {
            if (!active[i]) continue
            val a = sorted[i]
            selected.add(a)

            for (j in i + 1 until sorted.size) {
                if (!active[j]) continue
                val b = sorted[j]
                if (a.classId == b.classId && calculateIou(a.boundingBox, b.boundingBox) > iouThreshold) {
                    active[j] = false
                }
            }
        }

        return selected
    }

    private fun calculateIou(boxA: RectF, boxB: RectF): Float {
        val intersectionLeft = Math.max(boxA.left, boxB.left)
        val intersectionTop = Math.max(boxA.top, boxB.top)
        val intersectionRight = Math.min(boxA.right, boxB.right)
        val intersectionBottom = Math.min(boxA.bottom, boxB.bottom)

        val intersectionArea = Math.max(0.0f, intersectionRight - intersectionLeft) *
                Math.max(0.0f, intersectionBottom - intersectionTop)

        val boxAArea = boxA.width() * boxA.height()
        val boxBArea = boxB.width() * boxB.height()

        val unionArea = boxAArea + boxBArea - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0.0f
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
    }
}
