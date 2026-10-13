package com.shoppinglive.commerce.sales.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.orders.application.CreateOrderCommand;
import com.shoppinglive.commerce.orders.application.OrderCreationService;
import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.contracts.events.SalesInfoEvent;
import com.shoppinglive.contracts.events.SalesInfoEventContract;
import com.shoppinglive.contracts.events.SalesInfoEventType;
import com.shoppinglive.contracts.events.SalesInfoStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Opt-in host Kafka verification; salesEventsKafkaTest requires real PostgreSQL and broker addresses. */
@SpringBootTest(properties = {
    "commerce.sales.events.enabled=true",
    "spring.kafka.bootstrap-servers=${COMMERCE_TEST_KAFKA_SERVERS:localhost:29092}"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_KAFKA_SERVERS", matches = ".+")
class SalesInfoEventKafkaTest extends CommerceSecurityTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SalesService sales;
    @Autowired OrderCreationService creation;
    @Autowired OrderService orders;
    @Autowired InMemoryShoppingClientStub shopping;

    @Test
    void apiChangesPublishContractJsonAndStableKeyToRealKafka() throws Exception {
        String broker = System.getenv("COMMERCE_TEST_KAFKA_SERVERS");
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", broker))) {
            try {
                admin.createTopics(List.of(new NewTopic(SalesInfoEventContract.TOPIC_NAME, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exists) {
                if (!(exists.getCause() instanceof TopicExistsException)) throw exists;
            }
        }
        long productId = UUID.randomUUID().getMostSignificantBits() >>> 1;
        shopping.register(new ProductSnapshot(productId, "Kafka 검증 상품", null));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
            "bootstrap.servers", broker, "group.id", "commerce-sales-proof-" + UUID.randomUUID(),
            "enable.auto.commit", false, "key.deserializer", StringDeserializer.class,
            "value.deserializer", StringDeserializer.class))) {
            var partitions = consumer.partitionsFor(SalesInfoEventContract.TOPIC_NAME).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);

            String response = mvc.perform(post("/v1/sales").header("Authorization", adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + productId + ",\"price\":1000,\"initialStock\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            long id = mapper.readTree(response).get("id").asLong();
            assertThat(id).isPositive();
            sales.changePrice(id, 2000L);
            sales.changeStatus(id, SalesStatus.ON_SALE);
            String member = UUID.randomUUID().toString();
            var order = creation.create(new CreateOrderCommand(productId, 1, "구매자", "01012345678", member, 2000L), "kafka").order();
            orders.cancelBeforePayment(order.getOrderNumber(), member);

            List<SalesInfoEvent> events = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(20);
            while (events.size() < 7 && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    SalesInfoEvent event = mapper.readValue(record.value(), SalesInfoEvent.class);
                    if (event.payload().productId() != productId) continue;
                    assertThat(record.key()).isEqualTo(Long.toString(id));
                    assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                    assertThat(mapper.readTree(record.value()).get("occurredAt").isTextual()).isTrue();
                    events.add(event);
                }
            }
            assertThat(events).hasSize(7);
            assertThat(events).extracting(SalesInfoEvent::eventType).containsExactly(
                SalesInfoEventType.SalesRegistered, SalesInfoEventType.PriceChanged, SalesInfoEventType.SalesStatusChanged,
                SalesInfoEventType.InventoryChanged, SalesInfoEventType.SalesStatusChanged,
                SalesInfoEventType.InventoryChanged, SalesInfoEventType.SalesStatusChanged);
            assertThat(events).extracting(e -> e.payload().snapshotVersion()).containsExactly(1L, 2L, 3L, 4L, 4L, 5L, 5L);
            assertThat(events.getLast().payload().available()).isEqualTo(1);
            assertThat(events.getLast().payload().status()).isEqualTo(SalesInfoStatus.ON_SALE);
            assertThat(events).extracting(SalesInfoEvent::eventId).doesNotHaveDuplicates();
        }
    }
}
