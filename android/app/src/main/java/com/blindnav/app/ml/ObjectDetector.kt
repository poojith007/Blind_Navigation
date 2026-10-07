package com.blindnav.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections

class ObjectDetector(
    private val context: Context,
    private val preferredModelFileName: String = "yolo11n.onnx",
    private val fallbackModelFileName: String = "yolo11n.tflite",
    private val labelFileName: String = "labels.txt",
    private val confidenceThreshold: Float = 0.40f,
    private val iouThreshold: Float = 0.50f
) {

    private val tag = "ObjectDetector"

    // ONNX Runtime backend
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // TensorFlow Lite backend (fallback)
    private var tfliteInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private val labels = mutableListOf<String>()

    private val inputTensorWidth = 640
    private val inputTensorHeight = 640

    init {
        setupDetector()
    }

    private fun setupDetector() {
        loadLabels()

        // 1. First try loading ONNX model (preferred, full yolo11n.onnx 10.7MB is bundled)
        if (initOnnxSession(preferredModelFileName)) {
            Log.i(tag, "Successfully initialized primary detection engine: ONNX Runtime ($preferredModelFileName)")
            return
        }

        // 2. Fallback to TFLite model if ONNX was not loaded
        if (initTfliteInterpreter(fallbackModelFileName)) {
            Log.i(tag, "Successfully initialized fallback detection engine: TFLite ($fallbackModelFileName)")
            return
        }

        Log.e(tag, "Warning: Neither ONNX nor TFLite model could be initialized. Detections will be inactive.")
    }

    private fun loadLabels() {
        try {
            labels.clear()
            labels.addAll(FileUtil.loadLabels(context, labelFileName))
            Log.d(tag, "Loaded ${labels.size} labels from assets.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to load labels from $labelFileName: ${e.message}")
        }
    }

    private fun initOnnxSession(modelName: String): Boolean {
        return try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (!assetList.contains(modelName)) {
                Log.w(tag, "Asset '$modelName' not found in assets list.")
                return false
            }

            val modelBytes = context.assets.open(modelName).use { it.readBytes() }
            if (modelBytes.size < 1024) {
                Log.w(tag, "ONNX model '$modelName' appears to be a stub (${modelBytes.size} bytes). Skipping.")
                return false
            }

            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            ortEnvironment = env
            ortSession = env.createSession(modelBytes, sessionOptions)
            Log.i(tag, "ONNX Session created successfully. Input names: ${ortSession?.inputNames}, Output names: ${ortSession?.outputNames}")
            true
        } catch (e: Exception) {
            Log.w(tag, "Could not initialize ONNX session with '$modelName': ${e.message}")
            false
        }
    }

    private fun initTfliteInterpreter(modelName: String): Boolean {
        return try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (!assetList.contains(modelName)) {
                Log.w(tag, "Asset '$modelName' not found in assets list.")
                return false
            }

            val modelBuffer = FileUtil.loadMappedFile(context, modelName)
            if (modelBuffer.capacity() < 1024) {
                Log.w(tag, "TFLite file '$modelName' appears to be a placeholder (${modelBuffer.capacity()} bytes). Skipping.")
                return false
            }

            val options = Interpreter.Options()
            val compatList = CompatibilityList()

            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                gpuDelegate = GpuDelegate(delegateOptions)
                options.addDelegate(gpuDelegate)
                Log.d(tag, "TFLite initialized with GPU Delegate.")
            } else {
                options.setNumThreads(4)
                Log.d(tag, "TFLite initialized with 4 CPU threads.")
            }

            tfliteInterpreter = Interpreter(modelBuffer, options)
            true
        } catch (e: Exception) {
            Log.w(tag, "Could not initialize TFLite interpreter with '$modelName': ${e.message}")
            false
        }
    }

    fun isModelReady(): Boolean = ortSession != null || tfliteInterpreter != null

    @Volatile
    var lastInferenceMs: Long = 0L
        private set

    fun detect(bitmap: Bitmap): List<DetectedObject> {
        val start = android.os.SystemClock.elapsedRealtime()
        val result = when {
            ortSession != null -> detectWithOnnx(bitmap)
            tfliteInterpreter != null -> detectWithTflite(bitmap)
            else -> emptyList()
        }
        lastInferenceMs = android.os.SystemClock.elapsedRealtime() - start
        return result
    }

    private val area = inputTensorWidth * inputTensorHeight
    private val reusableIntValues = IntArray(area)
    private val reusableFloatBuffer: FloatBuffer = FloatBuffer.allocate(1 * 3 * area)

    // Reused across every frame for the TFLite path so we're not allocating a fresh
    // ~5MB input buffer and ~2.8MB output array on every single camera frame — that
    // allocation churn was adding avoidable GC pauses directly in the detection loop.
    private val tfliteInputBuffer: ByteBuffer = ByteBuffer.allocateDirect(4 * area * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val tfliteOutputBuffer = Array(1) { Array(84) { FloatArray(8400) } }
    private val tfliteIntValues = IntArray(area)

    // ==========================================
    // ONNX Inference Implementation
    // ==========================================
    @Synchronized
    private fun detectWithOnnx(bitmap: Bitmap): List<DetectedObject> {
        val session = ortSession ?: return emptyList()
        val env = ortEnvironment ?: return emptyList()

        val resizedBitmap = if (bitmap.width == inputTensorWidth && bitmap.height == inputTensorHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputTensorWidth, inputTensorHeight, true)
        }

        resizedBitmap.getPixels(reusableIntValues, 0, inputTensorWidth, 0, 0, inputTensorWidth, inputTensorHeight)
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        reusableFloatBuffer.clear()
        // Red channel
        for (i in 0 until area) {
            reusableFloatBuffer.put(((reusableIntValues[i] shr 16) and 0xFF) / 255.0f)
        }
        // Green channel
        for (i in 0 until area) {
            reusableFloatBuffer.put(((reusableIntValues[i] shr 8) and 0xFF) / 255.0f)
        }
        // Blue channel
        for (i in 0 until area) {
            reusableFloatBuffer.put((reusableIntValues[i] and 0xFF) / 255.0f)
        }
        reusableFloatBuffer.rewind()

        var inputTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        return try {
            inputTensor = OnnxTensor.createTensor(
                env,
                reusableFloatBuffer,
                longArrayOf(1, 3, inputTensorHeight.toLong(), inputTensorWidth.toLong())
            )
            val inputName = session.inputNames.iterator().next()
            results = session.run(Collections.singletonMap(inputName, inputTensor))

            val outTensor = results[0] as? OnnxTensor
            val shape = outTensor?.info?.shape
            if (outTensor != null && shape != null && shape.size == 3 && shape[1] < shape[2]) {
                // Fast path: one bulk copy into a reused flat array (no per-frame nested arrays).
                val channels = shape[1].toInt()
                val anchors = shape[2].toInt()
                if (flatOutput.size != channels * anchors) flatOutput = FloatArray(channels * anchors)
                val fb = outTensor.floatBuffer
                fb.rewind()
                fb.get(flatOutput)
                parseYoloFlat(flatOutput, channels, anchors)
            } else {
                val outputRaw = results[0].value
                val outputMatrix = extractOutputMatrix(outputRaw, outTensor)
                if (outputMatrix != null) parseYoloOutput(outputMatrix) else emptyList()
            }
        } catch (e: Exception) {
            Log.e(tag, "ONNX inference error: ${e.message}", e)
            emptyList()
        } finally {
            try { results?.close() } catch (_: Exception) {}
            try { inputTensor?.close() } catch (_: Exception) {}
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractOutputMatrix(outputRaw: Any?, tensor: OnnxTensor?): Array<FloatArray>? {
        if (outputRaw is Array<*>) {
            val first = outputRaw[0]
            if (first is Array<*> && first.isNotEmpty() && first[0] is FloatArray) {
                return first as Array<FloatArray>
            }
            if (first is FloatArray) {
                return outputRaw as Array<FloatArray>
            }
        }

        // Fallback: extract from direct FloatBuffer if multidimensional array cast is not available
        if (tensor != null) {
            val buffer = tensor.floatBuffer
            val numChannels = 84
            val numAnchors = 8400
            val matrix = Array(numChannels) { FloatArray(numAnchors) }
            for (c in 0 until numChannels) {
                for (a in 0 until numAnchors) {
                    matrix[c][a] = buffer.get()
                }
            }
            return matrix
        }
        return null
    }

    // ==========================================
    // TFLite Inference Implementation
    // ==========================================
    @Synchronized
    private fun detectWithTflite(bitmap: Bitmap): List<DetectedObject> {
        val tflite = tfliteInterpreter ?: return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputTensorWidth, inputTensorHeight, true)
        convertBitmapToByteBuffer(resizedBitmap, tfliteInputBuffer, tfliteIntValues)
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        return try {
            // YOLO11 output tensor shape: [1, 84, 8400]
            tflite.run(tfliteInputBuffer, tfliteOutputBuffer)
            parseYoloOutput(tfliteOutputBuffer[0])
        } catch (e: Exception) {
            Log.e(tag, "TFLite inference error: ${e.message}", e)
            emptyList()
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap, byteBuffer: ByteBuffer, intValues: IntArray) {
        byteBuffer.rewind()
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixelIndex = 0
        for (y in 0 until inputTensorHeight) {
            for (x in 0 until inputTensorWidth) {
                val value = intValues[pixelIndex++]
                byteBuffer.putFloat(((value shr 16 and 0xFF) / 255.0f))
                byteBuffer.putFloat(((value shr 8 and 0xFF) / 255.0f))
                byteBuffer.putFloat(((value and 0xFF) / 255.0f))
            }
        }
    }

    // ==========================================
    // Fast flat-array YOLO parsing (ONNX path)
    // ==========================================
    private var flatOutput = FloatArray(0)
    private var maxScores = FloatArray(0)
    private var maxClasses = IntArray(0)

    private fun parseYoloFlat(out: FloatArray, channels: Int, anchors: Int): List<DetectedObject> {
        if (maxScores.size != anchors) {
            maxScores = FloatArray(anchors)
            maxClasses = IntArray(anchors)
        }
        java.util.Arrays.fill(maxScores, 0f)
        java.util.Arrays.fill(maxClasses, -1)

        // Class-outer / anchor-inner keeps memory access sequential (the old
        // anchor-outer loop jumped across 80 separate arrays for every anchor).
        for (c in 4 until channels) {
            val base = c * anchors
            val cls = c - 4
            for (a in 0 until anchors) {
                val s = out[base + a]
                if (s > maxScores[a]) {
                    maxScores[a] = s
                    maxClasses[a] = cls
                }
            }
        }

        val candidates = mutableListOf<DetectedObject>()
        for (a in 0 until anchors) {
            val score = maxScores[a]
            val classId = maxClasses[a]
            if (score < confidenceThreshold || classId !in labels.indices) continue

            val cx = out[a] / inputTensorWidth
            val cy = out[anchors + a] / inputTensorHeight
            val w = out[2 * anchors + a] / inputTensorWidth
            val h = out[3 * anchors + a] / inputTensorHeight

            val rect = RectF(
                (cx - w / 2.0f).coerceIn(0.0f, 1.0f),
                (cy - h / 2.0f).coerceIn(0.0f, 1.0f),
                (cx + w / 2.0f).coerceIn(0.0f, 1.0f),
                (cy + h / 2.0f).coerceIn(0.0f, 1.0f)
            )
            val label = labels[classId]
            val distance = DistanceEstimator.estimateDistance(label, rect)
            candidates.add(
                DetectedObject(
                    classId = classId,
                    label = label,
                    confidence = score,
                    boundingBox = rect,
                    distanceMeters = distance,
                    position = DistanceEstimator.determinePosition(rect),
                    threatLevel = DistanceEstimator.determineThreatLevel(distance, label),
                    isInCorridor = DistanceEstimator.isInWalkingCorridor(rect, distance)
                )
            )
        }
        return applyNms(candidates)
    }

    // ==========================================
    // YOLO Output Post-Processing & NMS
    // ==========================================
    private fun parseYoloOutput(output: Array<FloatArray>): List<DetectedObject> {
        val candidates = mutableListOf<DetectedObject>()
        val numChannels = output.size // 84 (4 bbox + 80 class scores)
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

                val inCorridor = DistanceEstimator.isInWalkingCorridor(rect, distance)

                candidates.add(
                    DetectedObject(
                        classId = maxClassId,
                        label = label,
                        confidence = maxScore,
                        boundingBox = rect,
                        distanceMeters = distance,
                        position = position,
                        threatLevel = threatLevel,
                        isInCorridor = inCorridor
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
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing ONNX session: ${e.message}")
        }
        ortSession = null
        ortEnvironment = null

        try {
            tfliteInterpreter?.close()
            gpuDelegate?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing TFLite interpreter: ${e.message}")
        }
        tfliteInterpreter = null
        gpuDelegate = null
    }
}