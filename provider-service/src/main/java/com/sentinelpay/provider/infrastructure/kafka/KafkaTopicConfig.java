package com.sentinelpay.provider.infrastructure.kafka;

import com.sentinelpay.common.kafka.KafkaTopicDefaults;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic providerHealthChangedTopic() {
        return TopicBuilder.name("provider.health.changed")
                .partitions(3)
                .replicas(1)
                .configs(KafkaTopicDefaults.retentionConfig())
                .build();
    }
}
