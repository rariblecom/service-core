package com.rarible.core.loader.internal.notification

import com.rarible.core.daemon.RetryProperties
import com.rarible.core.daemon.sequential.ConsumerEventHandler
import com.rarible.core.daemon.sequential.ConsumerWorker
import com.rarible.core.daemon.sequential.ConsumerWorkerHolder
import com.rarible.core.kafka.RaribleKafkaConsumer
import com.rarible.core.kafka.json.JsonDeserializer
import com.rarible.core.loader.LoadNotification
import com.rarible.core.loader.LoadType
import com.rarible.core.loader.Loader
import com.rarible.core.loader.configuration.LOADER_PROPERTIES_PREFIX
import com.rarible.core.loader.configuration.LoadProperties
import com.rarible.core.loader.internal.common.LoadCommonConfiguration
import com.rarible.core.loader.internal.common.LoadKafkaTopicsRegistry
import kotlinx.coroutines.CancellationException
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories
import java.time.Duration


/**
 * Auto-configuration of the notifications part of the loader infrastructure.
 *
 * It defines the notification consumer beans that receive loading notifications and run associated listeners.
 */
@ConditionalOnProperty(
    prefix = LOADER_PROPERTIES_PREFIX,
    name = ["enableNotifications"],
    havingValue = "true",
    matchIfMissing = true
)
@Configuration
@Import(LoadCommonConfiguration::class)
@EnableReactiveMongoRepositories(basePackageClasses = [LoadNotificationPackage::class])
@ComponentScan(basePackageClasses = [LoadNotificationPackage::class])
class LoadNotificationConfiguration {

    private val logger = LoggerFactory.getLogger(LoadNotificationConfiguration::class.java)

    @Bean
    fun notificationListenersWorkers(
        loaders: List<Loader>,
        loadProperties: LoadProperties,
        loadNotificationListenersCaller: LoadNotificationListenersCaller,
        loadKafkaTopicsRegistry: LoadKafkaTopicsRegistry
    ): ConsumerWorkerHolder<LoadNotification> = ConsumerWorkerHolder(
        loaders.flatMap { loader ->
            notificationListenersWorkers(
                type = loader.type,
                loadProperties = loadProperties,
                loadNotificationListenersCaller = loadNotificationListenersCaller,
                loadKafkaTopicsRegistry = loadKafkaTopicsRegistry
            )
        }
    )

    @Bean
    fun loadNotificationConfigurationStartupLogger(
        loadProperties: LoadProperties
    ): CommandLineRunner = CommandLineRunner {
        logger.info("Loader notifications infrastructure has been initialized with properties $loadProperties")
    }

    private fun notificationListenersWorkers(
        type: LoadType,
        loadProperties: LoadProperties,
        loadNotificationListenersCaller: LoadNotificationListenersCaller,
        loadKafkaTopicsRegistry: LoadKafkaTopicsRegistry
    ): List<ConsumerWorker<LoadNotification>> {
        logger.info("Creating ${loadProperties.loadNotificationsTopicPartitions} notification listener workers")
        return (0 until loadProperties.loadNotificationsTopicPartitions).map { id ->
            val consumer = createConsumerForLoadNotifications(
                bootstrapServers = loadProperties.brokerReplicaSet,
                type = type,
                id = id,
                loadKafkaTopicsRegistry = loadKafkaTopicsRegistry
            )
            val notificationsLogger = LoggerFactory.getLogger("loading-notifications-$type")
            val workerName = "loader-notification-consumer-$type-$id"
            logger.info("Creating notification listener worker $workerName")
            ConsumerWorker(
                consumer = consumer,
                eventHandler = object : ConsumerEventHandler<LoadNotification> {
                    override suspend fun handle(event: LoadNotification) {
                        notificationsLogger.info("Received load notification $event")
                        loadNotificationListenersCaller.notifyListeners(event)
                    }
                },
                workerName = workerName,
                retryProperties = RetryProperties(attempts = 1, delay = Duration.ZERO),
                completionHandler = {
                    if (it != null && it !is CancellationException) {
                        logger.error("Notifications listener $workerName aborted because of a fatal error", it)
                    }
                }
            )
        }
    }

    private fun createConsumerForLoadNotifications(
        bootstrapServers: String,
        type: LoadType,
        id: Int,
        loadKafkaTopicsRegistry: LoadKafkaTopicsRegistry
    ): RaribleKafkaConsumer<LoadNotification> = RaribleKafkaConsumer(
        clientId = "loader-notifications-consumer-$type-$id",
        consumerGroup = "loader-notifications-$type",
        valueDeserializerClass = JsonDeserializer::class.java,
        defaultTopic = loadKafkaTopicsRegistry.getLoadNotificationsTopic(type),
        bootstrapServers = bootstrapServers,
        offsetResetStrategy = OffsetResetStrategy.EARLIEST,
        valueClass = LoadNotification::class.java,
        autoCreateTopic = false
    )

}
