package com.sentinelpay.payment.infrastructure.kafka;

import com.sentinelpay.common.kafka.KafkaTopicDefaults;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic paymentCompletedTopic() {
        return topic("payment.completed");
    }

    @Bean
    NewTopic paymentFailedTopic() {
        return topic("payment.failed");
    }

    @Bean
    NewTopic paymentRefundedTopic() {
        return topic("payment.refunded");
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(3)
                .replicas(1)
                .configs(KafkaTopicDefaults.retentionConfig())
                .build();
    }
}
