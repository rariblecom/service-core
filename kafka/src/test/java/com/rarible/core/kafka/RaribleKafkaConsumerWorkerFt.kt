package com.rarible.core.kafka

import com.rarible.core.kafka.json.JsonSerializer
import com.rarible.core.test.containers.KafkaTestContainer
import com.rarible.core.test.data.randomString
import com.rarible.core.test.wait.Wait.waitAssertWithCheckInterval
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

class RaribleKafkaConsumerWorkerFt {

    private val kafkaContainer = KafkaTestContainer()
    private val eventHandler = TestEventHandler()
    private val batchEventHandler = TestBatchEventHandler()
    private val factory = RaribleKafkaConsumerFactory("test", "localhost")

    private lateinit var topic: String
    private lateinit var group: String

    companion object {
        private val counter = AtomicInteger(0)
    }

    @BeforeEach
    fun beforeEach() {
        eventHandler.received.clear()
        batchEventHandler.received.clear()
        topic = "test-topic-" + randomString()
        group = "test-group-" + randomString()
    }

    @Test
    fun `receive message - ok, sync `() = runBlocking<Unit> {
        val producer = createProducer()
        val settings = createConsumerSettings(false)
        val containerSettings = createContainerSettings(1, 10)
        factory.createWorker(settings, eventHandler, createContainerFactory(containerSettings)).start()

        val events = createEvents(25)
        producer.send(events.map { KafkaMessage(key = randomString(), value = it) }).collect()

        waitAssert {
            assertThat(eventHandler.received).containsExactlyElementsOf(events)
        }
    }

    @Test
    fun `receive message - ok, async`() = runBlocking<Unit> {
        val producer = createProducer()
        val settings = createConsumerSettings(true)
        val containerSettings = createContainerSettings(3, 10)
        factory.createWorker(settings, eventHandler, createContainerFactory(containerSettings)).start()

        val events = createEvents(25)
        producer.send(events.map { KafkaMessage(key = randomString(), value = it) }).collect()

        waitAssert {
            assertThat(eventHandler.received).containsExactlyInAnyOrderElementsOf(events)
        }
    }

    @Test
    fun `receive message - ok, async with same key`() = runBlocking<Unit> {
        val producer = createProducer()
        val settings = createConsumerSettings(true)
        val containerSettings = createContainerSettings(3, 3)
        factory.createWorker(settings, eventHandler, createContainerFactory(containerSettings)).start()

        val events = createEvents(25)
        producer.send(events.map { KafkaMessage(key = "1", value = it) }).collect()

        waitAssert {
            assertThat(eventHandler.received).containsExactlyElementsOf(events)
        }
    }

    @Test
    fun `receive message - ok, several coroutine threads`() = runBlocking<Unit> {
        val producer = createProducer()
        val settings = createConsumerSettings(
            async = true,
            coroutineThreads = 3
        )
        val containerSettings = createContainerSettings(1, 10)
        factory.createWorker(settings, eventHandler, createContainerFactory(containerSettings)).start()

        val events = createEvents(105)
        producer.send(events.map { KafkaMessage(key = "1", value = it) }).collect()

        waitAssert {
            assertThat(eventHandler.received).containsExactlyElementsOf(events)
        }
    }

    @Test
    fun `receive message - ok, batch consumer, sync`() = runBlocking<Unit> {
        val producer = createProducer()
        val settings = createConsumerSettings(false)
        val containerSettings = createContainerSettings(1, 3)
        factory.createWorker(settings, batchEventHandler, createContainerFactory(containerSettings)).start()

        val events = createEvents(25)
        producer.send(events.map { KafkaMessage(key = randomString(), value = it) }).collect()

        waitAssert {
            assertThat(batchEventHandler.received).containsExactlyElementsOf(events)
        }
    }

    @Test
    fun `receive message - ok, batch consumer, sync with same key`() = runBlocking<Unit> {
        val producer = createProducer()
        val settings = createConsumerSettings(false)
        val containerSettings = createContainerSettings(3, 10)
        factory.createWorker(settings, batchEventHandler, createContainerFactory(containerSettings)).start()

        val events = createEvents(25)
        producer.send(events.map { KafkaMessage(key = "1", value = it) }).collect()

        waitAssert {
            assertThat(batchEventHandler.received).containsExactlyElementsOf(events)
        }
    }

    @Test
    fun `receive message - ok, batch consumer, async`() = runBlocking<Unit> {
        val producer = createProducer()
        val settings = createConsumerSettings(true)
        val containerSettings = createContainerSettings(3, 10)
        factory.createWorker(settings, batchEventHandler, createContainerFactory(containerSettings)).start()

        val events = createEvents(25)
        producer.send(events.map { KafkaMessage(key = randomString(), value = it) }).collect()

        waitAssert {
            assertThat(batchEventHandler.received).containsExactlyInAnyOrderElementsOf(events)
        }
    }

    @Test
    fun `receive message - ok, batch consumer, async with same key`() = runBlocking<Unit> {
        val producer = createProducer()
        val consumerSettings = createConsumerSettings(true)
        val containerSettings = createContainerSettings(1, 10)
        factory.createWorker(consumerSettings, batchEventHandler, createContainerFactory(containerSettings)).start()

        val events = createEvents(25)
        producer.send(events.map { KafkaMessage(key = "1", value = it) }).collect()

        waitAssert {
            assertThat(batchEventHandler.received).containsExactlyElementsOf(events)
        }
    }

    private fun createProducer(): RaribleKafkaProducer<TestEvent> {
        return RaribleKafkaProducer(
            clientId = group + "-" + randomString(),
            valueSerializerClass = JsonSerializer::class.java,
            valueClass = TestEvent::class.java,
            defaultTopic = topic,
            bootstrapServers = kafkaContainer.kafkaBoostrapServers()
        )
    }

    private fun createContainerFactory(settings: RaribleKafkaContainerFactorySettings<TestEvent>) =
        RaribleKafkaListenerContainerFactory(
            settings = settings
        )

    private fun createContainerSettings(
        concurrency: Int,
        batchSize: Int,
    ): RaribleKafkaContainerFactorySettings<TestEvent> = RaribleKafkaContainerFactorySettings(
        hosts = kafkaContainer.kafkaBoostrapServers(),
        valueClass = TestEvent::class.java,
        concurrency = concurrency,
        batchSize = batchSize,
        offsetResetStrategy = OffsetResetStrategy.EARLIEST
    )

    private fun createConsumerSettings(
        async: Boolean,
        coroutineThreads: Int = 1,
    ): RaribleKafkaConsumerSettings {
        return RaribleKafkaConsumerSettings(
            topic = topic,
            group = group,
            async = async,
            coroutineThreadCount = coroutineThreads
        )
    }

    private fun createEvents(count: Int): List<TestEvent> {
        return (1..count).map {
            TestEvent(randomString(), counter.incrementAndGet())
        }
    }

    private suspend fun waitAssert(runnable: suspend () -> Unit) {
        return waitAssertWithCheckInterval(
            checkInterval = Duration.ofMillis(50),
            timeout = Duration.ofSeconds(5),
            runnable = runnable
        )
    }
}

class TestEventHandler : RaribleKafkaEventHandler<TestEvent> {

    val received = ConcurrentLinkedDeque<TestEvent>()

    override suspend fun handle(event: TestEvent) {
        received.add(event)
    }
}

class TestBatchEventHandler : RaribleKafkaBatchEventHandler<TestEvent> {

    val received = ConcurrentLinkedDeque<TestEvent>()

    override suspend fun handle(event: List<TestEvent>) {
        received.addAll(event)
    }
}

data class TestEvent(val name: String, val age: Int)
