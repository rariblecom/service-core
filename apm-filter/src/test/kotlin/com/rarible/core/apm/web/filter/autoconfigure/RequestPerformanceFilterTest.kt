package com.rarible.core.apm.web.filter.autoconfigure

import com.rarible.core.apm.CaptureSpan
import com.rarible.core.apm.CaptureTransaction
import com.rarible.core.apm.getApmContext
import com.rarible.core.apm.web.filter.RequestPerformanceFilter
import com.rarible.core.application.ApplicationEnvironmentInfo
import com.rarible.core.application.ApplicationInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import reactor.core.publisher.Mono

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "rarible.core.filter.apm.enabled=true",
        "rarible.core.apm.annotation.enabled=true"
]
)
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(RequestPerformanceFilterTest.Configuration::class)
class RequestPerformanceFilterTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var requestPerformanceFilter: RequestPerformanceFilter

    @Autowired
    private lateinit var testAnnotatedClass: TestAnnotatedClass

    @Test
    fun `test apm filter initialized`() {
        assertThat(requestPerformanceFilter).isNotNull
    }

    @Test
    fun `APM context propagated`() {
        val template = RestTemplate()
        val result: Boolean = template.getForObject("http://localhost:${port}/test")
        assertThat(result).isTrue()
    }

    @Test
    fun `should handle transaction annotation`() {
        testAnnotatedClass
            .openTransaction()
            .subscribe {  }
    }

    @TestConfiguration
    internal class Configuration {
        @Bean
        fun applicationEnvironmentInfo(): ApplicationEnvironmentInfo {
            return ApplicationEnvironmentInfo("test", "test.com")
        }

        @Bean
        fun applicationInfo(): ApplicationInfo {
            return ApplicationInfo("test", "test.com")
        }

        @Bean
        fun testAnnotatedClass(): TestAnnotatedClass {
            return TestAnnotatedClass()
        }
    }

    @RestController
    internal class TestController {
        @GetMapping("/test")
        suspend fun test(): Boolean {
            val ctx = getApmContext()
            return ctx != null
        }
    }

    class TestAnnotatedClass {
        @CaptureTransaction("transaction")
        fun openTransaction(): Mono<String> {
            return Mono
                .just("Open Transaction")
                .then(openSpan())
        }

        @CaptureSpan(value = "span", type = "test", subtype = "sub", action = "testAction")
        fun openSpan(): Mono<String> {
            return Mono.just("Open Span")
        }
    }
}
