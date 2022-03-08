package com.rarible.core.content.meta.loader

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.MetadataException
import com.drew.metadata.avi.AviDirectory
import com.drew.metadata.bmp.BmpHeaderDirectory
import com.drew.metadata.eps.EpsDirectory
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifImageDirectory
import com.drew.metadata.file.FileTypeDirectory
import com.drew.metadata.gif.GifHeaderDirectory
import com.drew.metadata.gif.GifImageDirectory
import com.drew.metadata.heif.HeifDirectory
import com.drew.metadata.ico.IcoDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.mov.media.QuickTimeVideoDirectory
import com.drew.metadata.mp3.Mp3Directory
import com.drew.metadata.mp4.media.Mp4VideoDirectory
import com.drew.metadata.photoshop.PsdHeaderDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.wav.WavDirectory
import com.drew.metadata.webp.WebpDirectory
import com.rarible.core.common.nowMillis
import org.slf4j.LoggerFactory
import java.net.URL
import java.time.Duration
import kotlin.time.toJavaDuration

class ContentMetaReceiver(
    private val contentReceiver: ContentReceiver,
    private val maxBytes: Int,
    private val contentReceiverMetrics: ContentReceiverMetrics
) {
    private val logger = LoggerFactory.getLogger(ContentMetaReceiver::class.java)

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun receive(url: String): ContentMeta? = receive(URL(url))

    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun receive(url: URL): ContentMeta? {
        getPredefinedContentMeta(url)?.let { return it }
        val logPrefix = "Content meta by $url"
        logger.info("$logPrefix: started receiving")
        val startSample = contentReceiverMetrics.startReceiving()
        return try {
            val contentMeta = doReceive(url, logPrefix)
            val duration = contentReceiverMetrics.endReceiving(startSample, true)
            if (contentMeta != null) {
                logger.info(
                    "$logPrefix: received $contentMeta (in ${duration.presentableSlow(slowThreshold)})"
                )
                contentMeta
            } else {
                null
            }
        } catch (e: Throwable) {
            val duration = contentReceiverMetrics.endReceiving(startSample, false)
            logger.warn(
                "$logPrefix: failed to receive content meta" +
                        " (in ${duration.presentableSlow(slowThreshold)})", e
            )
            null
        }
    }

    private fun getWithExtensionFallback(url: URL): ContentMeta? {
        val extension = url.toExternalForm().substringAfterLast(".", "")
        val mimeType = extensionMapping[extension] ?: return null
        return ContentMeta(
            type = mimeType,
        )
    }

    private fun getPredefinedContentMeta(url: URL): ContentMeta? {
        val extension = url.toExternalForm().substringAfterLast(".")
        val mediaType = ignoredExtensions[extension] ?: return null
        return ContentMeta(mediaType)
    }

    private suspend fun doReceive(url: URL, logPrefix: String): ContentMeta? {
        val startLoading = nowMillis()
        val contentBytes = try {
            contentReceiver.receiveBytes(url, maxBytes)
        } catch (e: Exception) {
            logger.warn(
                "$logPrefix: failed to get content bytes in " +
                        Duration.between(startLoading, nowMillis()).presentableSlow(slowThreshold)
            )
            throw e
        }
        val receiveTime = Duration.between(startLoading, nowMillis())
        logger.info(
            "$logPrefix: received content " +
                    "(bytes ${contentBytes.bytes.size}, " +
                    "content length ${contentBytes.contentLength}, " +
                    "mime type ${contentBytes.contentType}) " +
                    "in ${receiveTime.presentableSlow(slowThreshold)}"
        )
        val bytes = contentBytes.bytes
        contentReceiverMetrics.receivedBytes(bytes.size)
        parseSvg(contentBytes)?.let {
            logger.info("$logPrefix: parsed SVG content meta $it")
            return it
        }

        PngDetector.detectPngContentMeta(contentBytes)?.let {
            logger.info("$logPrefix: parsed PNG content meta $it")
            return it
        }

        @Suppress("BlockingMethodInNonBlockingContext")
        val metadata = try {
            ImageMetadataReader.readMetadata(bytes.inputStream())
        } catch (e: Exception) {
            val fallbackMeta = if (contentBytes.contentType != null) {
                if (
                    contentBytes.contentType.startsWith("image/")
                    || contentBytes.contentType.startsWith("video/")
                    || contentBytes.contentType.startsWith("audio/")
                    || contentBytes.contentType.startsWith("model/")
                ) {
                    ContentMeta(
                        type = contentBytes.contentType,
                        size = contentBytes.contentLength
                    )
                } else {
                    null
                }
            } else {
                getWithExtensionFallback(url)?.copy(size = contentBytes.contentLength)
            }
            logger.warn(
                "$logPrefix: failed to extract metadata by ${bytes.size} bytes, fallback meta " +
                        if (fallbackMeta != null) "$fallbackMeta" else "is empty",
                e
            )
            return fallbackMeta
        }
        var mimeType: String? = null
        var width: Int? = null
        var height: Int? = null
        var errors = 0
        for (directory in metadata.directories) {
            if (directory is FileTypeDirectory) {
                mimeType = directory.safeString(FileTypeDirectory.TAG_DETECTED_FILE_MIME_TYPE, url)
            }
            parseImageOrVideoWidthAndHeight(directory, url)?.let {
                width = width ?: it.first
                height = height ?: it.second
            }
            errors += directory.errors.toList().size
        }
        return ContentMeta(
            type = mimeType ?: contentBytes.contentType ?: return null,
            width = width,
            height = height,
            size = contentBytes.contentLength
        )
    }

    private fun parseSvg(contentBytes: ContentBytes): ContentMeta? {
        if (contentBytes.contentType == svgMimeType || contentBytes.bytes.take(svgPrefix.size) == svgPrefix) {
            return ContentMeta(
                type = svgMimeType,
                width = 192,
                height = 192,
                size = contentBytes.contentLength
            )
        }
        return null
    }

    private fun parseImageOrVideoWidthAndHeight(directory: Directory, url: URL): Pair<Int?, Int?>? {
        return when (directory) {
            // Images
            is JpegDirectory -> {
                directory.safeInt(JpegDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(JpegDirectory.TAG_IMAGE_HEIGHT, url)
            }
            is GifImageDirectory -> {
                directory.safeInt(GifImageDirectory.TAG_WIDTH, url) to
                        directory.safeInt(GifImageDirectory.TAG_HEIGHT, url)
            }
            is GifHeaderDirectory -> {
                directory.safeInt(GifHeaderDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(GifHeaderDirectory.TAG_IMAGE_HEIGHT, url)
            }
            is BmpHeaderDirectory -> {
                directory.safeInt(BmpHeaderDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(BmpHeaderDirectory.TAG_IMAGE_HEIGHT, url)
            }
            is PngDirectory -> {
                directory.safeInt(PngDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(PngDirectory.TAG_IMAGE_HEIGHT, url)
            }
            is IcoDirectory -> {
                directory.safeInt(IcoDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(IcoDirectory.TAG_IMAGE_HEIGHT, url)
            }
            is PsdHeaderDirectory -> {
                directory.safeInt(PsdHeaderDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(PsdHeaderDirectory.TAG_IMAGE_HEIGHT, url)
            }
            is WebpDirectory -> {
                directory.safeInt(WebpDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(WebpDirectory.TAG_IMAGE_HEIGHT, url)
            }
            is EpsDirectory -> {
                directory.safeInt(EpsDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(EpsDirectory.TAG_IMAGE_HEIGHT, url)
            }
            is ExifImageDirectory -> {
                directory.safeInt(ExifDirectoryBase.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(ExifDirectoryBase.TAG_IMAGE_HEIGHT, url)
            }
            is HeifDirectory -> {
                directory.safeInt(HeifDirectory.TAG_IMAGE_WIDTH, url) to
                        directory.safeInt(HeifDirectory.TAG_IMAGE_HEIGHT, url)
            }
            // Video
            is QuickTimeVideoDirectory -> {
                directory.safeInt(QuickTimeVideoDirectory.TAG_WIDTH, url) to
                        directory.safeInt(QuickTimeVideoDirectory.TAG_HEIGHT, url)
            }
            is AviDirectory -> {
                directory.safeInt(AviDirectory.TAG_WIDTH, url) to
                        directory.safeInt(AviDirectory.TAG_HEIGHT, url)
            }
            is Mp4VideoDirectory -> {
                directory.safeInt(Mp4VideoDirectory.TAG_WIDTH, url) to
                        directory.safeInt(Mp4VideoDirectory.TAG_HEIGHT, url)
            }
            // Audio
            is Mp3Directory -> null
            is WavDirectory -> null
            else -> null
        }
    }

    private fun Directory.safeInt(tagId: Int, url: URL): Int? = safe(tagId, url) { getInt(tagId) }
    private fun Directory.safeString(tagId: Int, url: URL): String? = safe(tagId, url) { getString(tagId) }

    private fun <T> Directory.safe(tagId: Int, url: URL, parser: () -> T): T? = try {
        parser()
    } catch (e: MetadataException) {
        logger.warn("Failed to parse tag " + getTagName(tagId) + " from $url of parsed to $this", e)
        null
    }

    private companion object {
        private val slowThreshold = Duration.ofSeconds(1)

        val ignoredExtensions = mapOf(
            "mp3" to "audio/mp3",
            "wav" to "audio/wav",
            "flac" to "audio/flac",
            "mpga" to "audio/mpeg",

            "gltf" to "model/gltf+json",
            "glb" to "model/gltf-binary"
        )

        val extensionMapping = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "bmp" to "image/bmp",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "avi" to "video/x-msvideo",
            "mpeg" to "video/mpeg"
        )

        const val svgMimeType = "image/svg+xml"
        val svgPrefix = "data:image/svg+xml".toByteArray(Charsets.UTF_8).toList()
    }
}
