package com.rarible.core.kafka

import com.rarible.core.kafka.json.JsonDeserializer
import com.rarible.core.kafka.json.JsonSerializer
import com.rarible.core.test.containers.KafkaTestContainer
import com.rarible.core.test.data.randomInt
import com.rarible.core.test.data.randomString
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.withTimeout
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.Duration

data class TestObject(val field1: String, val field2: Int)

@FlowPreview
@Disabled
internal class RaribleKafkaConsumerTest {
    private val kafkaContainer = KafkaTestContainer()

    @Test
    fun sendReceiveKafkaMessage() = runBlocking<Unit> {
        val producer = RaribleKafkaProducer(
            clientId = "test-producer",
            valueSerializerClass = JsonSerializer::class.java,
            valueClass = TestObject::class.java,
            defaultTopic = "test-topic",
            bootstrapServers = kafkaContainer.kafkaBoostrapServers()
        )
        val consumer = RaribleKafkaConsumer(
            clientId = "test-consumer",
            consumerGroup = "test-group",
            valueDeserializerClass = JsonDeserializer::class.java,
            valueClass = TestObject::class.java,
            defaultTopic = "test-topic",
            bootstrapServers = kafkaContainer.kafkaBoostrapServers(),
            offsetResetStrategy = OffsetResetStrategy.EARLIEST
        )
        val sendResult = withTimeout(Duration.ofSeconds(5)) {
            val headers = hashMapOf("header1" to "value1", "header2" to "value2")
            producer.send(KafkaMessage(key = "key", value = TestObject("field1", 1), headers = headers))
        }

        assertThat(sendResult.isSuccess).isEqualTo(true)

        val received = withTimeout(Duration.ofSeconds(5)) {
            consumer.receive().first()
        }

        assertThat(received.key).isEqualTo("key")
        assertThat(received.value.field1).isEqualTo("field1")
        assertThat(received.value.field2).isEqualTo(1)
        assertThat(received.headers.size).isEqualTo(3)
        assertThat(received.headers["header1"]).isEqualTo("value1")
        assertThat(received.headers["header2"]).isEqualTo("value2")
    }

    @Test
    fun `create topic with N partitions, send M messages and consume all with C consumers`() = runBlocking<Unit> {
        val topicName = "testTopic-" + System.currentTimeMillis()
        val countMessages = 100
        val countPartitions = 5
        val countConsumers = 3
        RaribleKafkaTopics.createTopic(kafkaContainer.kafkaBoostrapServers(), topicName, countPartitions)
        val producer = RaribleKafkaProducer(
            clientId = "test-producer",
            valueSerializerClass = JsonSerializer::class.java,
            valueClass = TestObject::class.java,
            defaultTopic = topicName,
            bootstrapServers = kafkaContainer.kafkaBoostrapServers()
        )
        val testObjects = (0 until countMessages).map { TestObject(field1 = randomString(), field2 = randomInt()) }
        producer.send(testObjects.map { KafkaMessage(it.field1, it) }, topicName).collect()

        val consumersGroup = "test-group-for-$topicName"
        val consumers = (0 until countConsumers).map {
            RaribleKafkaConsumer(
                clientId = "test-consumer-$it",
                consumerGroup = consumersGroup,
                valueDeserializerClass = JsonDeserializer::class.java,
                valueClass = TestObject::class.java,
                defaultTopic = topicName,
                bootstrapServers = kafkaContainer.kafkaBoostrapServers(),
                offsetResetStrategy = OffsetResetStrategy.EARLIEST
            )
        }
        val countReceivedMessages = consumers.sumOf { it.receive().receiveAll().count() }
        assertThat(countReceivedMessages).isEqualTo(countMessages)
    }

    private suspend fun <T> Flow<T>.receiveAll(): List<T> {
        val result = arrayListOf<T>()
        try {
            withTimeout(Duration.ofSeconds(1)) {
                collect { result += it }
            }
        } catch (ignored: Exception) {
        }
        return result
    }
}
