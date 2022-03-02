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
import org.slf4j.LoggerFactory
import java.net.URL

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
        val startSample = contentReceiverMetrics.startReceiving()
        return try {
            val contentMeta = doReceive(url)
            contentReceiverMetrics.endReceiving(startSample, true)
            contentMeta
        } catch (e: Throwable) {
            contentReceiverMetrics.endReceiving(startSample, false)
            throw e
        }
    }

    private fun getPredefinedContentMeta(url: URL): ContentMeta? {
        val extension = url.toExternalForm().substringAfterLast(".")
        val mediaType = ignoredExtensions[extension] ?: return null
        return ContentMeta(mediaType)
    }

    private suspend fun doReceive(url: URL): ContentMeta? {
        val logPrefix = "Content meta by $url"
        logger.info("$logPrefix: started receiving")
        val contentBytes = contentReceiver.receiveBytes(url, maxBytes)
        logger.info(
            "$logPrefix: received content " +
                    "(bytes ${contentBytes.bytes.size}, " +
                    "content length ${contentBytes.contentLength}, " +
                    "mime type ${contentBytes.contentType})"
        )
        val bytes = contentBytes.bytes
        contentReceiverMetrics.receivedBytes(bytes.size)
        parseSvg(contentBytes)?.let {
            logger.info("$logPrefix: parsed SVG content meta $it")
            return it
        }

        @Suppress("BlockingMethodInNonBlockingContext")
        val metadata = try {
            ImageMetadataReader.readMetadata(bytes.inputStream())
        } catch (e: Exception) {
            val fallbackMeta = if (contentBytes.contentType != null) {
                ContentMeta(
                    type = contentBytes.contentType,
                    size = contentBytes.contentLength
                )
            } else {
                null
            }
            logger.info(
                "$logPrefix: failed to extract metadata by ${bytes.size} bytes" +
                        if (fallbackMeta != null) " fallback meta $fallbackMeta" else ""
            )
            return null
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
        val contentMeta = ContentMeta(
            type = mimeType ?: contentBytes.contentType ?: return null,
            width = width,
            height = height,
            size = contentBytes.contentLength
        )
        logger.info("$logPrefix: received content meta: $contentMeta (errors extracting $errors)")
        return contentMeta
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
        val ignoredExtensions = mapOf(
            "mp3" to "audio/mp3",
            "wav" to "audio/wav",
            "flac" to "audio/flac",
            "mpga" to "audio/mpeg",

            "gltf" to "model/gltf+json",
            "glb" to "model/gltf-binary"
        )

        const val svgMimeType = "image/svg+xml"
        val svgPrefix = "data:image/svg+xml".toByteArray(Charsets.UTF_8).toList()
    }
}
