package com.example.core.moderation

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

sealed interface ModerationResult {
    data object Safe : ModerationResult
    data class Blocked(val reason: String) : ModerationResult
}

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class FrameSkinMetrics(
    val totalSkinRatio: Float,
    val centerTorsoSkinRatio: Float,
    val largestClusterRatio: Float,
    val borderSkinRatio: Float = 0f,
    val skinLumaStdDev: Float = 20f,
    val faceCoverageRatio: Float = 0f,
    val likelyUniformBackdrop: Boolean = false,
    val lowerCenterSkinRatio: Float = 0f,
    val chestCoreSkinRatio: Float = -1f,
    val pelvicCoreSkinRatio: Float = -1f,
    val hasTwoPieceClothingBands: Boolean = false,
    val hasPelvicGarmentBand: Boolean = false,
    val hasChestGarmentBand: Boolean = false,
    val foregroundRatio: Float = 1f,
    val nonSkinForegroundRatio: Float = 0f,
    val hasSegmentationMask: Boolean = false,
    val isWaistUpPortrait: Boolean = false,
    val skinTextureComplexity: Float = 10f,
    val faceCount: Int = 0
) {
    val effectiveChestCore: Float
        get() = if (chestCoreSkinRatio >= 0f) chestCoreSkinRatio else centerTorsoSkinRatio

    val effectivePelvicCore: Float
        get() = if (pelvicCoreSkinRatio >= 0f) pelvicCoreSkinRatio else lowerCenterSkinRatio

    val hasSubstantialClothing: Boolean
        get() = hasSegmentationMask &&
            foregroundRatio in 0.06f..0.95f &&
            nonSkinForegroundRatio >= 0.34f

    val isExplicitCandidate: Boolean
        get() = !likelyUniformBackdrop &&
            !hasTwoPieceClothingBands &&
            !hasPelvicGarmentBand &&
            !hasSubstantialClothing &&
            !isWaistUpPortrait &&
            skinLumaStdDev >= 8.5f &&
            skinTextureComplexity >= 2.0f && (
                // Continuous unclothed torso + pelvic/genital region without clothing or swimwear break
                (centerTorsoSkinRatio >= 0.60f &&
                    lowerCenterSkinRatio >= 0.58f &&
                    effectivePelvicCore >= 0.58f &&
                    effectiveChestCore >= 0.48f &&
                    largestClusterRatio >= 0.35f) ||
                // Full-frame close-up explicit bare body exposure
                (totalSkinRatio >= 0.66f &&
                    largestClusterRatio >= 0.52f &&
                    centerTorsoSkinRatio >= 0.60f &&
                    effectivePelvicCore >= 0.50f)
            )
}

object VideoContentModerator {

    const val BLOCKED_MESSAGE =
        "Upload blocked: Pornographic and explicit sexual activity content is not permitted on Blade."

    private data class CachedVerdict(
        val result: ModerationResult,
        val timestampMs: Long
    )

    private val recentVerdictsLock = Any()
    private val recentVerdicts = object : LinkedHashMap<String, CachedVerdict>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedVerdict>?): Boolean {
            return size > 16
        }
    }
    private const val CACHE_TTL_MS = 120_000L

    // Strictly genuine hardcore pornography domains, explicit sexual intercourse acts, and pornographic media identifiers.
    // Intentionally excludes body-part words, clothing/swimwear terms, partial exposure/nudity words,
    // dance/fitness/modeling terms, profanity, slang, and age markers ("18+", "nsfw", "sexy", "hot", "nude", etc.)
    // so normal videos are never falsely flagged by keywords alone.
    private val hardcorePornTokens = setOf(
        "porn", "porno", "pornography", "pornographic", "pornhub", "xvideos", "xvideo", "xnxx",
        "xhamster", "redtube", "youporn", "brazzers", "spankbang", "tube8", "eporner", "txxx",
        "javhd", "beeg", "tnaflix", "sunporno", "motherless", "chaturbate", "stripchat",
        "bongacams", "livejasmin", "cam4", "myfreecams", "manyvids", "clips4sale",
        "xxx", "xxxx", "hentai",
        "blowjob", "handjob", "rimjob", "cumshot", "creampie", "bukkake",
        "deepthroat", "cunnilingus", "fellatio", "doggystyle", "fisting",
        "lesbianporn", "gayporn", "gangbang", "orgy", "incest",
        "masturbate", "masturbation",
        "pornvideo", "sexvideo", "sextape", "sexclip", "sexfilm", "bluefilm"
    )

    private val hardcorePornPhrases = listOf(
        "porn video", "porn clip", "porn film", "hardcore porn", "explicit porn",
        "sex video", "sex clip", "sex tape", "sex film", "blue film",
        "xxx video", "xxx clip", "live sex", "cam sex",
        "পর্ন", "পর্ন ভিডিও", "সেক্স ভিডিও", "ব্লু ফিল্ম"
    )

    /**
     * Evaluates whether metadata contains genuine pornography production/act indicators.
     * Note: Never used alone to reject an uploaded video when visual analysis runs —
     * serves only as supporting context alongside contextual visual analysis.
     */
    fun hasExplicitPornographyMetadata(
        title: String,
        caption: String,
        location: String,
        fileName: String? = null,
        additionalFileNames: List<String> = emptyList()
    ): Boolean {
        val combinedRaw = (listOfNotNull(title, caption, location, fileName) + additionalFileNames)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        if (combinedRaw.isBlank()) return false

        val phraseSearchText = combinedRaw
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        for (phrase in hardcorePornPhrases) {
            if (containsWholePhrase(phraseSearchText, phrase)) {
                return true
            }
        }

        val tokens = Regex("[a-z0-9\\u0980-\\u09FF]+")
            .findAll(phraseSearchText)
            .map { it.value }
            .toList()

        if (tokens.any { it in hardcorePornTokens }) {
            return true
        }

        // Obfuscated hardcore porn tokens within whitespace segments (e.g. "p.o.r.n", "#x_x_x")
        val strippedTokens = combinedRaw
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-z0-9]"), "") }
        if (strippedTokens.any { it in hardcorePornTokens }) {
            return true
        }

        return false
    }

    /**
     * Metadata-only safety helper kept conservative: does NOT block body exposure, clothing,
     * dance/fitness/modeling terms, profanity, or general adult slang.
     */
    fun checkMetadataSafety(
        title: String,
        caption: String,
        location: String,
        fileName: String? = null,
        additionalFileNames: List<String> = emptyList()
    ): ModerationResult {
        return if (
            hasExplicitPornographyMetadata(
                title = title,
                caption = caption,
                location = location,
                fileName = fileName,
                additionalFileNames = additionalFileNames
            )
        ) {
            ModerationResult.Blocked(BLOCKED_MESSAGE)
        } else {
            ModerationResult.Safe
        }
    }

    private fun containsWholePhrase(text: String, phrase: String): Boolean {
        val pattern = Regex("(?<![a-z0-9\\u0980-\\u09FF])${Regex.escape(phrase)}(?![a-z0-9\\u0980-\\u09FF])")
        return pattern.containsMatchIn(text)
    }

    suspend fun moderateRemoteVideo(
        remoteUrl: String,
        title: String,
        caption: String,
        location: String
    ): ModerationResult = withContext(Dispatchers.IO) {
        val cleanUrl = remoteUrl.trim()
        val parsedUri = runCatching { Uri.parse(cleanUrl) }.getOrNull()
        val parsedFileName = parsedUri?.lastPathSegment?.takeIf { it.isNotBlank() } ?: cleanUrl
        val urlExtras = listOfNotNull(parsedUri?.host, parsedUri?.path, parsedUri?.query)
        val hasPornMetaSignal = hasExplicitPornographyMetadata(
            title = title,
            caption = caption,
            location = location,
            fileName = parsedFileName,
            additionalFileNames = urlExtras
        )

        val remoteMetrics = if (
            cleanUrl.startsWith("http://", ignoreCase = true) ||
            cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            withTimeoutOrNull(7_000L) {
                sampleAndAnalyzeRemoteFrames(cleanUrl)
            }.orEmpty()
        } else {
            emptyList()
        }

        evaluateFrameMetrics(
            frameMetrics = remoteMetrics,
            hasPornographicMetadataSignal = hasPornMetaSignal
        )
    }

    suspend fun moderateVideo(
        contentResolver: ContentResolver,
        videoUri: Uri,
        title: String,
        caption: String,
        location: String,
        durationMs: Long,
        isPhoto: Boolean = false,
        additionalFileNames: List<String> = emptyList()
    ): ModerationResult = withContext(Dispatchers.IO) {
        val fileName = resolveDisplayName(contentResolver, videoUri)
        val fileSize = resolveFileSize(contentResolver, videoUri)
        val extrasKey = additionalFileNames.joinToString(",") { it.trim() }
        val cacheKey = "$videoUri|$fileSize|${title.trim()}|${caption.trim()}|${location.trim()}|$durationMs|$isPhoto|$extrasKey"
        val now = System.currentTimeMillis()
        synchronized(recentVerdictsLock) {
            val cached = recentVerdicts[cacheKey]
            if (cached != null && now - cached.timestampMs <= CACHE_TTL_MS) {
                return@withContext cached.result
            }
        }

        // Always analyze actual visual content first; never block solely on title/keywords.
        val hasPornMetaSignal = hasExplicitPornographyMetadata(
            title = title,
            caption = caption,
            location = location,
            fileName = fileName,
            additionalFileNames = additionalFileNames
        )

        val frameMetrics = if (isPhoto) {
            samplePhotoFrame(contentResolver, videoUri)
        } else {
            sampleAndAnalyzeFrames(contentResolver, videoUri, durationMs)
        }
        val verdict = evaluateFrameMetrics(
            frameMetrics = frameMetrics,
            hasPornographicMetadataSignal = hasPornMetaSignal
        )
        cacheVerdict(cacheKey, verdict, now)
        verdict
    }

    fun resolveDisplayNameForModeration(contentResolver: ContentResolver, uri: Uri): String? {
        return resolveDisplayName(contentResolver, uri)
    }

    fun evaluateFrameMetrics(
        frameMetrics: List<FrameSkinMetrics>,
        hasPornographicMetadataSignal: Boolean = false
    ): ModerationResult {
        if (frameMetrics.isEmpty()) return ModerationResult.Safe

        val explicitFrames = frameMetrics.filter { it.isExplicitCandidate }
        val flaggedCount = explicitFrames.size
        if (flaggedCount == 0) return ModerationResult.Safe

        val avgTotalSkin = explicitFrames.map { it.totalSkinRatio }.average().toFloat()
        val avgTorsoSkin = explicitFrames.map { it.centerTorsoSkinRatio }.average().toFloat()
        val avgLowerCenterSkin = explicitFrames.map { it.lowerCenterSkinRatio }.average().toFloat()
        val avgPelvicCore = explicitFrames.map { it.effectivePelvicCore }.average().toFloat()
        val avgClusterSkin = explicitFrames.map { it.largestClusterRatio }.average().toFloat()

        val shouldBlock = when {
            frameMetrics.size == 1 -> {
                val minTotal = if (hasPornographicMetadataSignal) 0.52f else 0.56f
                flaggedCount == 1 &&
                    avgTotalSkin >= minTotal &&
                    avgTorsoSkin >= 0.60f &&
                    avgPelvicCore >= 0.58f &&
                    avgClusterSkin >= 0.42f
            }
            frameMetrics.size == 2 -> {
                flaggedCount == 2 &&
                    avgTorsoSkin >= 0.58f &&
                    avgLowerCenterSkin >= 0.56f &&
                    avgPelvicCore >= 0.58f &&
                    avgClusterSkin >= 0.34f
            }
            frameMetrics.size <= 5 -> {
                flaggedCount >= 3 || (
                    flaggedCount >= 2 &&
                        hasPornographicMetadataSignal &&
                        avgTorsoSkin >= 0.58f &&
                        avgLowerCenterSkin >= 0.56f &&
                        avgClusterSkin >= 0.34f
                )
            }
            else -> {
                val pelvicClearFrames = frameMetrics.count {
                    !it.hasPelvicGarmentBand && !it.hasTwoPieceClothingBands && !it.likelyUniformBackdrop
                }
                flaggedCount >= 3 || (
                    flaggedCount >= 2 && (
                        (avgClusterSkin >= 0.56f && avgPelvicCore >= 0.68f && pelvicClearFrames >= 5) ||
                            (hasPornographicMetadataSignal &&
                                avgTorsoSkin >= 0.58f &&
                                avgLowerCenterSkin >= 0.56f &&
                                avgPelvicCore >= 0.58f)
                        )
                )
            }
        }
        return if (shouldBlock) {
            ModerationResult.Blocked(BLOCKED_MESSAGE)
        } else {
            ModerationResult.Safe
        }
    }

    private fun cacheVerdict(key: String, result: ModerationResult, now: Long) {
        synchronized(recentVerdictsLock) {
            recentVerdicts[key] = CachedVerdict(result = result, timestampMs = now)
        }
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment
        }.getOrNull()
    }

    private fun resolveFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null && f.exists()) return f.length()
        }
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun createFaceDetector(): FaceDetector? = runCatching {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.10f)
                .build()
        )
    }.getOrNull()

    private fun createSegmenter(): Segmenter? = runCatching {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }.getOrNull()

    private suspend fun samplePhotoFrame(
        contentResolver: ContentResolver,
        photoUri: Uri
    ): List<FrameSkinMetrics> {
        val rawBitmap = runCatching {
            contentResolver.openInputStream(photoUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return emptyList()

        val faceDetector = createFaceDetector()
        val segmenter = createSegmenter()
        return try {
            listOf(analyzeBitmapWithContextualAi(rawBitmap, faceDetector, segmenter))
        } finally {
            runCatching { faceDetector?.close() }
            runCatching { segmenter?.close() }
            if (!rawBitmap.isRecycled) rawBitmap.recycle()
        }
    }

    private suspend fun sampleAndAnalyzeFrames(
        contentResolver: ContentResolver,
        videoUri: Uri,
        fallbackDurationMs: Long
    ): List<FrameSkinMetrics> {
        val retriever = MediaMetadataRetriever()
        val faceDetector = createFaceDetector()
        val segmenter = createSegmenter()
        val results = mutableListOf<FrameSkinMetrics>()
        var tempCopy: File? = null
        try {
            var sourceReady = runCatching {
                contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    true
                } ?: false
            }.getOrDefault(false)

            if (!sourceReady) {
                val copied = File.createTempFile("blade_mod_", ".mp4")
                tempCopy = copied
                contentResolver.openInputStream(videoUri)?.use { input ->
                    copied.outputStream().use { out -> input.copyTo(out) }
                }
                if (copied.length() > 0L) {
                    retriever.setDataSource(copied.absolutePath)
                    sourceReady = true
                }
            }

            if (!sourceReady) return emptyList()

            val actualDurationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: fallbackDurationMs.coerceAtLeast(1000L)

            val fractions = listOf(0.06f, 0.17f, 0.29f, 0.42f, 0.55f, 0.68f, 0.81f, 0.93f)
            for (fraction in fractions) {
                val timeUs = (actualDurationMs * fraction).toLong().coerceAtLeast(0L) * 1000L
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs)
                    ?: continue
                try {
                    results.add(analyzeBitmapWithContextualAi(rawBitmap, faceDetector, segmenter))
                } finally {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                }
            }
        } catch (_: Exception) {
            // If codec/URI cannot be decoded on device, fallback safely
        } finally {
            runCatching { faceDetector?.close() }
            runCatching { segmenter?.close() }
            runCatching { retriever.release() }
            runCatching { tempCopy?.delete() }
        }
        return results
    }

    private suspend fun sampleAndAnalyzeRemoteFrames(remoteUrl: String): List<FrameSkinMetrics> {
        val retriever = MediaMetadataRetriever()
        val faceDetector = createFaceDetector()
        val segmenter = createSegmenter()
        val results = mutableListOf<FrameSkinMetrics>()
        try {
            retriever.setDataSource(remoteUrl, emptyMap())
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: 10_000L

            val fractions = listOf(0.15f, 0.40f, 0.65f, 0.85f)
            for (fraction in fractions) {
                val timeUs = (durationMs * fraction).toLong().coerceAtLeast(0L) * 1000L
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs)
                    ?: continue
                try {
                    results.add(analyzeBitmapWithContextualAi(rawBitmap, faceDetector, segmenter))
                } finally {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                }
            }
        } catch (_: Exception) {
            // Remote host or stream unreadable by MediaMetadataRetriever
        } finally {
            runCatching { faceDetector?.close() }
            runCatching { segmenter?.close() }
            runCatching { retriever.release() }
        }
        return results
    }

    private suspend fun analyzeBitmapWithContextualAi(
        rawBitmap: Bitmap,
        faceDetector: FaceDetector?,
        segmenter: Segmenter?
    ): FrameSkinMetrics {
        val aiBitmap = scalePreservingAspect(rawBitmap, maxDimension = 320)
        val analysisBitmap = scalePreservingAspect(rawBitmap, maxDimension = 144)
        return try {
            val inputImage = InputImage.fromBitmap(aiBitmap, 0)
            val excludedFaces = detectNormalizedFaceBoxes(faceDetector, inputImage, aiBitmap.width, aiBitmap.height)
            val foregroundMask = detectForegroundMask(
                segmenter = segmenter,
                inputImage = inputImage,
                targetWidth = analysisBitmap.width,
                targetHeight = analysisBitmap.height
            )
            analyzeFrameBitmap(analysisBitmap, excludedFaces, foregroundMask)
        } finally {
            if (aiBitmap !== rawBitmap && !aiBitmap.isRecycled) {
                aiBitmap.recycle()
            }
            if (analysisBitmap !== rawBitmap && !analysisBitmap.isRecycled) {
                analysisBitmap.recycle()
            }
        }
    }

    private fun scalePreservingAspect(source: Bitmap, maxDimension: Int): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val maxSide = max(w, h)
        if (maxSide <= maxDimension) return source
        val scale = maxDimension.toFloat() / maxSide.toFloat()
        val targetW = (w * scale).roundToInt().coerceAtLeast(1)
        val targetH = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, false)
    }

    private suspend fun detectNormalizedFaceBoxes(
        detector: FaceDetector?,
        inputImage: InputImage,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<NormalizedBox> {
        if (detector == null) return emptyList()
        val w = bitmapWidth.toFloat().coerceAtLeast(1f)
        val h = bitmapHeight.toFloat().coerceAtLeast(1f)
        return withTimeoutOrNull(1200L) {
            runCatching {
                val faces = suspendCancellableCoroutine { cont ->
                    detector.process(inputImage)
                        .addOnSuccessListener { list ->
                            if (cont.isActive) cont.resume(list)
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                }
                faces.map { face ->
                    val box = face.boundingBox
                    val bw = box.width().toFloat()
                    val bh = box.height().toFloat()
                    NormalizedBox(
                        left = ((box.left - bw * 0.08f) / w).coerceIn(0f, 1f),
                        top = ((box.top - bh * 0.14f) / h).coerceIn(0f, 1f),
                        right = ((box.right + bw * 0.08f) / w).coerceIn(0f, 1f),
                        bottom = ((box.bottom + bh * 0.14f) / h).coerceIn(0f, 1f)
                    )
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    private suspend fun detectForegroundMask(
        segmenter: Segmenter?,
        inputImage: InputImage,
        targetWidth: Int,
        targetHeight: Int
    ): BooleanArray? {
        if (segmenter == null || targetWidth <= 0 || targetHeight <= 0) return null
        return withTimeoutOrNull(1200L) {
            runCatching {
                val segMask = suspendCancellableCoroutine<SegmentationMask?> { cont ->
                    segmenter.process(inputImage)
                        .addOnSuccessListener { mask ->
                            if (cont.isActive) cont.resume(mask)
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                } ?: return@runCatching null

                val mw = segMask.width
                val mh = segMask.height
                if (mw <= 0 || mh <= 0) return@runCatching null
                val rawBuf = segMask.buffer.duplicate()
                rawBuf.rewind()
                val floatBuf = rawBuf.asFloatBuffer()
                val maxIdx = (mw * mh - 1).coerceAtLeast(0)
                val out = BooleanArray(targetWidth * targetHeight)
                var fgCount = 0

                for (y in 0 until targetHeight) {
                    val my = (y * mh) / targetHeight
                    for (x in 0 until targetWidth) {
                        val mx = (x * mw) / targetWidth
                        val conf = floatBuf.get((my * mw + mx).coerceIn(0, maxIdx))
                        val isFg = conf >= 0.42f
                        out[y * targetWidth + x] = isFg
                        if (isFg) fgCount++
                    }
                }

                val fgRatio = fgCount.toFloat() / out.size.toFloat()
                // Ignore extreme close-up saturation (>=97%) where background is completely outside the frame
                if (fgRatio >= 0.97f) null else out
            }.getOrNull()
        }
    }

    fun analyzeFrameBitmap(
        bitmap: Bitmap,
        excludedRegions: List<NormalizedBox> = emptyList(),
        foregroundMask: BooleanArray? = null
    ): FrameSkinMetrics {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height
        if (totalPixels <= 0) return FrameSkinMetrics(0f, 0f, 0f)
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return analyzeFramePixels(pixels, width, height, excludedRegions, foregroundMask)
    }

    fun analyzeFramePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        excludedRegions: List<NormalizedBox> = emptyList(),
        foregroundMask: BooleanArray? = null
    ): FrameSkinMetrics {
        val totalPixels = width * height
        if (totalPixels <= 0 || pixels.size < totalPixels) return FrameSkinMetrics(0f, 0f, 0f)

        val useMask = foregroundMask != null && foregroundMask.size >= totalPixels
        val lumaMap = FloatArray(totalPixels)
        val skinMask = BooleanArray(totalPixels)
        var skinCount = 0
        var excludedCount = 0
        var fgTotal = 0
        var fgExcludedCount = 0
        var fgNonSkinBodyCount = 0
        var torsoTotal = 0
        var torsoSkin = 0
        var lowerTotal = 0
        var lowerSkin = 0
        var chestCoreTotal = 0
        var chestCoreSkin = 0
        var pelvicCoreTotal = 0
        var pelvicCoreSkin = 0
        var borderTotal = 0
        var borderSkin = 0
        var lumaSum = 0.0
        var lumaSqSum = 0.0

        val rowMin = (height * 0.25f).toInt()
        val rowMax = (height * 0.88f).toInt()
        val colMin = (width * 0.15f).toInt()
        val colMax = (width * 0.85f).toInt()
        val lowerRowMin = (height * 0.45f).toInt()
        val lowerRowMax = (height * 0.92f).toInt()
        val lowerColMin = (width * 0.18f).toInt()
        val lowerColMax = (width * 0.82f).toInt()
        val coreColMin = (width * 0.28f).toInt()
        val coreColMax = (width * 0.72f).toInt()
        val bandColMin = (width * 0.28f).toInt()
        val bandColMax = (width * 0.72f).toInt()
        val chestCoreRowMin = (height * 0.30f).toInt()
        val chestCoreRowMax = (height * 0.48f).toInt()
        val pelvicCoreRowMin = (height * 0.54f).toInt()
        val pelvicCoreRowMax = (height * 0.78f).toInt()
        val chestBandRowMin = (height * 0.26f).toInt()
        val chestBandRowMax = (height * 0.52f).toInt()
        val pelvicBandRowMin = (height * 0.50f).toInt()
        val pelvicBandRowMax = (height * 0.88f).toInt()
        val rowBandTotals = IntArray(height)
        val rowBandSkins = IntArray(height)
        val rim = 2

        for (y in 0 until height) {
            val ny = (y + 0.5f) / height.toFloat()
            val isBorderY = y < rim || y >= height - rim
            for (x in 0 until width) {
                val idx = y * width + x
                val nx = (x + 0.5f) / width.toFloat()
                val inExcludedFace = excludedRegions.any { box ->
                    nx in box.left..box.right && ny in box.top..box.bottom
                }
                if (inExcludedFace) {
                    excludedCount++
                }

                val px = pixels[idx]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                val luma = (0.299f * r + 0.587f * g + 0.114f * b)
                lumaMap[idx] = luma

                val isForeground = !useMask || foregroundMask!![idx]
                if (isForeground) {
                    fgTotal++
                    if (inExcludedFace) fgExcludedCount++
                }

                val isRawSkin = isSkinPixel(r, g, b)
                val isBodySkin = !inExcludedFace && isForeground && isRawSkin
                skinMask[idx] = isBodySkin

                if (isForeground && !inExcludedFace && !isBodySkin) {
                    fgNonSkinBodyCount++
                }

                if (isBodySkin) {
                    skinCount++
                    lumaSum += luma
                    lumaSqSum += luma * luma
                }

                if (y in rowMin..rowMax && x in colMin..colMax) {
                    torsoTotal++
                    if (isBodySkin) torsoSkin++
                }

                if (y in lowerRowMin..lowerRowMax && x in lowerColMin..lowerColMax) {
                    lowerTotal++
                    if (isBodySkin) lowerSkin++
                }

                if (x in coreColMin..coreColMax && y in chestCoreRowMin..chestCoreRowMax) {
                    chestCoreTotal++
                    if (isBodySkin) chestCoreSkin++
                }

                if (x in coreColMin..coreColMax && y in pelvicCoreRowMin..pelvicCoreRowMax) {
                    pelvicCoreTotal++
                    if (isBodySkin) pelvicCoreSkin++
                }

                if (x in bandColMin..bandColMax && !inExcludedFace && (!useMask || isForeground)) {
                    rowBandTotals[y]++
                    if (isBodySkin) rowBandSkins[y]++
                }

                if (isBorderY || x < rim || x >= width - rim) {
                    borderTotal++
                    if (isRawSkin) borderSkin++
                }
            }
        }

        // Compute local anatomical/shading gradient complexity inside body skin pixels
        var textureDeltaSum = 0.0
        var textureDeltaCount = 0
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val idx = y * width + x
                if (skinMask[idx]) {
                    val cur = lumaMap[idx]
                    val dx = abs(cur - lumaMap[idx + 1])
                    val dy = abs(cur - lumaMap[idx + width])
                    textureDeltaSum += (dx + dy) * 0.5
                    textureDeltaCount++
                }
            }
        }
        val skinTextureComplexity = if (textureDeltaCount > 0) {
            (textureDeltaSum / textureDeltaCount.toDouble()).toFloat()
        } else {
            0f
        }

        val faceCoverageRatio = excludedCount.toFloat() / totalPixels.toFloat()
        val effectiveDenominator = if (faceCoverageRatio >= 0.30f) {
            totalPixels
        } else {
            (totalPixels - excludedCount).coerceAtLeast(1)
        }

        val foregroundRatio = if (useMask) fgTotal.toFloat() / totalPixels.toFloat() else 1f
        val fgBodyDenominator = (fgTotal - fgExcludedCount).coerceAtLeast(1)
        val nonSkinForegroundRatio = if (useMask) {
            fgNonSkinBodyCount.toFloat() / fgBodyDenominator.toFloat()
        } else {
            0f
        }

        val largestCluster = findLargestConnectedSkinRegion(skinMask, width, height)
        val borderSkinRatio = if (borderTotal > 0) borderSkin.toFloat() / borderTotal.toFloat() else 0f
        val skinLumaStdDev = if (skinCount > 1) {
            val mean = lumaSum / skinCount.toDouble()
            val variance = (lumaSqSum / skinCount.toDouble()) - (mean * mean)
            sqrt(max(0.0, variance)).toFloat()
        } else {
            0f
        }

        // Uniform beige walls, wooden desks, sand, or full-screen warm color filters flood borders with flat variance
        val likelyUniformBackdrop = (borderSkinRatio >= 0.72f && skinLumaStdDev < 14f) ||
            (borderSkinRatio >= 0.96f && skinLumaStdDev < 18f)

        fun hasCoveredGarmentBand(startY: Int, endY: Int, minContiguousRows: Int): Boolean {
            var run = 0
            for (y in startY.coerceIn(0, height - 1)..endY.coerceIn(0, height - 1)) {
                val rowTotal = rowBandTotals[y]
                if (rowTotal > 0) {
                    val rowSkinRatio = rowBandSkins[y].toFloat() / rowTotal.toFloat()
                    if (rowSkinRatio <= 0.28f) {
                        run++
                        if (run >= minContiguousRows) return true
                    } else {
                        run = 0
                    }
                } else {
                    run = 0
                }
            }
            return false
        }

        val hasChestGarmentBand = hasCoveredGarmentBand(
            startY = chestBandRowMin,
            endY = chestBandRowMax,
            minContiguousRows = max(2, (height * 0.04f).toInt())
        )
        val hasPelvicGarmentBand = hasCoveredGarmentBand(
            startY = pelvicBandRowMin,
            endY = pelvicBandRowMax,
            minContiguousRows = max(2, (height * 0.04f).toInt())
        )

        val pelvicCoreRatio = if (pelvicCoreTotal > 0) pelvicCoreSkin.toFloat() / pelvicCoreTotal.toFloat() else 0f

        // Detect waist-up shirtless gym / fitness / mirror selfie framing where the camera cuts off
        // around chest/stomach level (pelvis is physically below the bottom edge of the frame)
        val isWaistUpPortrait = excludedRegions.any { box ->
            val boxH = (box.bottom - box.top).coerceAtLeast(0f)
            val boxW = (box.right - box.left).coerceAtLeast(0f)
            val centerY = (box.top + box.bottom) * 0.5f
            val actualFaceHeight = boxH / 1.28f
            val estimatedWaistReachY = box.bottom + actualFaceHeight * 2.15f
            centerY <= 0.42f &&
                actualFaceHeight >= 0.20f &&
                boxW >= 0.16f &&
                estimatedWaistReachY >= 0.85f &&
                pelvicCoreRatio < 0.88f
        }

        return FrameSkinMetrics(
            totalSkinRatio = skinCount.toFloat() / effectiveDenominator.toFloat(),
            centerTorsoSkinRatio = if (torsoTotal > 0) torsoSkin.toFloat() / torsoTotal.toFloat() else 0f,
            largestClusterRatio = largestCluster.toFloat() / effectiveDenominator.toFloat(),
            borderSkinRatio = borderSkinRatio,
            skinLumaStdDev = skinLumaStdDev,
            faceCoverageRatio = faceCoverageRatio,
            likelyUniformBackdrop = likelyUniformBackdrop,
            lowerCenterSkinRatio = if (lowerTotal > 0) lowerSkin.toFloat() / lowerTotal.toFloat() else 0f,
            chestCoreSkinRatio = if (chestCoreTotal > 0) chestCoreSkin.toFloat() / chestCoreTotal.toFloat() else 0f,
            pelvicCoreSkinRatio = pelvicCoreRatio,
            hasTwoPieceClothingBands = hasChestGarmentBand && hasPelvicGarmentBand,
            hasPelvicGarmentBand = hasPelvicGarmentBand,
            hasChestGarmentBand = hasChestGarmentBand,
            foregroundRatio = foregroundRatio,
            nonSkinForegroundRatio = nonSkinForegroundRatio,
            hasSegmentationMask = useMask,
            isWaistUpPortrait = isWaistUpPortrait,
            skinTextureComplexity = skinTextureComplexity,
            faceCount = excludedRegions.size
        )
    }

    private fun isSkinPixel(r: Int, g: Int, b: Int): Boolean {
        if (r < 40 || g < 20 || b < 12) return false
        if (r > 246 && g > 238 && b > 228) return false
        val maxRgb = max(r, max(g, b))
        val minRgb = min(r, min(g, b))
        if (r != maxRgb || (r - g) < 8 || (maxRgb - minRgb) < 14) return false

        // Reject oversaturated neon red/orange sunsets, LED lights, and spicy curry/tomato tints
        val saturation = (maxRgb - minRgb).toFloat() / maxRgb.toFloat()
        if (saturation >= 0.72f) return false

        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return y in 35.0..238.0 && cb in 75.0..129.0 && cr in 131.0..178.0
    }

    private fun findLargestConnectedSkinRegion(mask: BooleanArray, width: Int, height: Int): Int {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var maxRegion = 0

        for (i in mask.indices) {
            if (!mask[i] || visited[i]) continue
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true
            var count = 0

            while (head < tail) {
                val cur = queue[head++]
                count++
                val cx = cur % width
                val cy = cur / width

                if (cx > 0) {
                    val left = cur - 1
                    if (mask[left] && !visited[left]) {
                        visited[left] = true
                        queue[tail++] = left
                    }
                }
                if (cx + 1 < width) {
                    val right = cur + 1
                    if (mask[right] && !visited[right]) {
                        visited[right] = true
                        queue[tail++] = right
                    }
                }
                if (cy > 0) {
                    val up = cur - width
                    if (mask[up] && !visited[up]) {
                        visited[up] = true
                        queue[tail++] = up
                    }
                }
                if (cy + 1 < height) {
                    val down = cur + width
                    if (mask[down] && !visited[down]) {
                        visited[down] = true
                        queue[tail++] = down
                    }
                }
            }
            if (count > maxRegion) maxRegion = count
        }
        return maxRegion
    }
}
