package com.sentinelpay.risk.infrastructure.kafka;

import com.sentinelpay.common.kafka.KafkaTopicDefaults;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic riskAssessedTopic() {
        return topic("risk.assessed");
    }

    @Bean
    NewTopic fraudAlertHighTopic() {
        return topic("fraud.alert.high");
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(3)
                .replicas(1)
                .configs(KafkaTopicDefaults.retentionConfig())
                .build();
    }
}
