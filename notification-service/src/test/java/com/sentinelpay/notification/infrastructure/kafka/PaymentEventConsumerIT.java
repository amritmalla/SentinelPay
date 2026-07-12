package com.sentinelpay.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.common.events.TenantContext;
import com.sentinelpay.notification.infrastructure.persistence.DeliveryAttemptRepository;
import com.sentinelpay.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class PaymentEventConsumerIT {

    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

    @Container
    static GenericContainer<?> mailhog =
            new GenericContainer<>(DockerImageName.parse("mailhog/mailhog")).withExposedPorts(1025, 8025);

    @Autowired
    KafkaTemplate<String, EventEnvelope> dltKafkaTemplate;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "notification-it-" + UUID.randomUUID());
        registry.add("spring.mail.host", mailhog::getHost);
        registry.add("spring.mail.port", () -> mailhog.getMappedPort(1025));
    }

    @Test
    void duplicateEventId_producesOneNotificationAndOneMailHogMessage() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                "payment.completed",
                Instant.now(),
                "payment-service",
                "corr-it-1",
                new TenantContext(merchantId),
                Map.of(
                        "merchantId", merchantId.toString(),
                        "paymentId", paymentId.toString(),
                        "customerEmail", "buyer@example.com",
                        "amountCents", 2500));

        dltKafkaTemplate.send("payment.completed", merchantId.toString(), envelope).get();
        dltKafkaTemplate.send("payment.completed", merchantId.toString(), envelope).get();

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(notificationRepository.countByEventId(eventId)).isEqualTo(1);
            assertThat(deliveryAttemptRepository.count()).isEqualTo(1);
            assertThat(mailhogMessageCount()).isEqualTo(1);
        });
    }

    private int mailhogMessageCount() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://"
                + mailhog.getHost()
                + ":"
                + mailhog.getMappedPort(8025)
                + "/api/v2/messages");
        HttpResponse<String> response =
                client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        return root.get("total").asInt();
    }
}
