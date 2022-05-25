package com.rarible.core.meta.resource.detector.ethereum

import com.rarible.core.meta.resource.detector.MimeType
import com.rarible.core.meta.resource.detector.embedded.EmbeddedBase64Detector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmbeddedBase64DetectorTest {

    private val detector = EmbeddedBase64Detector

    @Test
    fun `is base64 url`() {
        assertThat(detector.isDetected(BASE_64)).isTrue
        assertThat(detector.isDetected("https://some.data.com/ipfs/abc/image.png")).isFalse
    }

    @Test
    fun `get base64 image parts`() {
        assertThat(detector.getData(BASE_64)).isEqualTo("abc")
        assertThat(detector.getMimeType(BASE_64)).isEqualTo(MimeType.PNG_IMAGE.value)
    }

    @Test
    fun `get base64 image test text type`() {
        assertThat(detector.getData(BASE_64_TEXT_TYPE)).isEqualTo("abc")
        assertThat(detector.getMimeType(BASE_64_TEXT_TYPE)).isEqualTo(MimeType.HTML_TEXT.value)
    }

    companion object {
        private const val BASE_64 = "https://some.data.com/data:image/png;base64,abc"
        private const val BASE_64_TEXT_TYPE = "https://some.data.com/data:text/html;base64,abc"
    }
}
