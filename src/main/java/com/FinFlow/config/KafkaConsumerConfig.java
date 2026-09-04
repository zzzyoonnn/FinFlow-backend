package com.FinFlow.config;

import com.FinFlow.event.InvalidEventPayloadException;
import com.FinFlow.event.UnsupportedEventSchemaException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "finflow.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
      @Value("${finflow.kafka.consumer.retry-interval:1000}") long retryInterval,
      @Value("${finflow.kafka.consumer.max-retries:3}") long maxRetries,
      @Value("${finflow.kafka.consumer.dlq-suffix:.dlq}") String dlqSuffix) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
        (record, exception) -> new TopicPartition(record.topic() + dlqSuffix, record.partition()));
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
        new FixedBackOff(retryInterval, maxRetries));
    errorHandler.addNotRetryableExceptions(InvalidEventPayloadException.class,
        UnsupportedEventSchemaException.class);
    errorHandler.setCommitRecovered(true);
    return errorHandler;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConsumerFactory<Object, Object> consumerFactory,
      DefaultErrorHandler kafkaErrorHandler) {
    ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    configurer.configure(factory, consumerFactory);
    factory.setCommonErrorHandler(kafkaErrorHandler);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    return factory;
  }
}
