package com.rarible.core.meta.resource.detector.ethereum

import com.rarible.core.meta.resource.detector.MimeType
import com.rarible.core.meta.resource.detector.embedded.EmbeddedSvgDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class EmbeddedSvgDetectorTest {

    private val detector = EmbeddedSvgDetector

    @Test
    fun `svg detector do not react to strings without svg tag`() {
        assertThat(detector.isDetected("url")).isFalse
    }

    @Test
    fun `svg detector is able to recognize svg tag`() {
        assertThat(detector.isDetected(SVG_URL)).isTrue
    }

    @Test
    fun `get svg image parts`() {
        assertThat(detector.getData(SVG_URL)).isEqualTo(DECODED_SVG)
        assertThat(detector.getMimeType(SVG_URL)).isEqualTo(MimeType.SVG_XML_IMAGE.value)
    }

    @Test
    fun `can decode svg images`() {
        val svg = String(Files.readAllBytes(Paths.get(this::class.java.getResource("/meta/resource/detector/ethereum/test.svg").toURI())));
        assertThat(detector.isDetected(svg)).isTrue
        assertThat(detector.getData(svg)).isNotEmpty
    }

    companion object {
        private const val SVG_URL = "https://some.data.com/data:image/svg+xml;utf8,<svg%20class='nft'><rect%20class='c217'%20x='10'%20y='12'%20width='2'%20height='1' fill:%23AAAAAA/></svg>"
        private const val DECODED_SVG = "<svg class='nft'><rect class='c217' x='10' y='12' width='2' height='1' fill:#AAAAAA/></svg>"
    }
}
