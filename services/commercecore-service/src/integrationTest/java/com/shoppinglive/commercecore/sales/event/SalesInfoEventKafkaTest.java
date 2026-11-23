package com.shoppinglive.commercecore.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.order.dto.CreateOrderCommand;
import com.shoppinglive.commercecore.order.service.OrderCreationService;
import com.shoppinglive.commercecore.order.service.OrderService;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sku.service.SkuSalesService;
import com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 격리된 외부 Kafka 브로커 주소를 지정했을 때 판매 이벤트 전달을 검증한다. */
@SpringBootTest(
        properties = {
            "shoppinglive.outbox.relay.enabled=true",
            "spring.kafka.bootstrap-servers=${COMMERCECORE_TEST_KAFKA_SERVERS:localhost:29092}"
        })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_KAFKA_SERVERS", matches = ".+")
class SalesInfoEventKafkaTest extends CommerceCoreSecurityTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SkuSalesService sales;
    @Autowired OrderCreationService creation;
    @Autowired OrderService orders;
    @Autowired InMemoryProductLookupStub productLookup;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @DisplayName("API 변경은 실제 Kafka에 계약 JSON과 고정된 키를 발행한다")
    @Test
    void apiChangesPublishContractJsonAndStableKeyToRealKafka() throws Exception {
        String broker = System.getenv("COMMERCECORE_TEST_KAFKA_SERVERS");
        List<TopicPartition> partitions;
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", broker))) {
            try {
                admin.createTopics(
                                List.of(
                                        new NewTopic(
                                                SalesInfoEventContract.TOPIC_NAME, 1, (short) 1)))
                        .all()
                        .get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exists) {
                if (!(exists.getCause() instanceof TopicExistsException)) throw exists;
            }
            // 이미 존재하는 토픽의 실제 전체 파티션을 확인한다.
            var descriptions =
                    admin.describeTopics(List.of(SalesInfoEventContract.TOPIC_NAME))
                            .allTopicNames()
                            .get(10, TimeUnit.SECONDS);
            assertThat(descriptions.keySet()).containsExactly(SalesInfoEventContract.TOPIC_NAME);
            var actualPartitions = descriptions.get(SalesInfoEventContract.TOPIC_NAME).partitions();
            assertThat(actualPartitions).isNotEmpty();
            assertThat(actualPartitions.stream().map(partition -> partition.partition()).toList())
                    .containsExactlyInAnyOrderElementsOf(
                            IntStream.range(0, actualPartitions.size()).boxed().toList());
            partitions =
                    actualPartitions.stream()
                            .map(
                                    partition ->
                                            new TopicPartition(
                                                    SalesInfoEventContract.TOPIC_NAME,
                                                    partition.partition()))
                            .toList();
        }
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ClassPathResource("cart/sku-fixture.sql"))
                .execute(jdbc.getDataSource());
        long productId = UUID.randomUUID().getMostSignificantBits() >>> 1;
        productLookup.register(new ProductSnapshot(productId, "Kafka 검증 상품", null, SELLER, true));
        jdbc.update(
                "INSERT INTO product(id,name,description,main_image_id,seller_id,sale_enabled,version,created_at,updated_at) VALUES (?,?,'Kafka 검증',1,?,TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                productId,
                "Kafka 검증 상품",
                SELLER);
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(
                        Map.of(
                                "bootstrap.servers",
                                broker,
                                "group.id",
                                "commerce-sales-proof-" + UUID.randomUUID(),
                                "enable.auto.commit",
                                false,
                                "key.deserializer",
                                StringDeserializer.class,
                                "value.deserializer",
                                StringDeserializer.class))) {
            consumer.assign(partitions);
            assertThat(consumer.assignment()).containsExactlyInAnyOrderElementsOf(partitions);
            consumer.seekToEnd(partitions);
            // HTTP 등록과 후속 변경 전에 모든 파티션의 소비 위치를 확정한다.
            for (var partition : partitions) {
                assertThat(consumer.position(partition, Duration.ofSeconds(10)))
                        .isGreaterThanOrEqualTo(0);
            }

            String response =
                    mvc.perform(
                                    post("/v1/sales")
                                            .header("Authorization", sellerBearer())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"productId\":"
                                                            + productId
                                                            + ",\"price\":1000,\"initialStock\":1}"))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            long id = mapper.readTree(response).get("id").asLong();
            assertThat(id).isPositive();
            sales.changePrice(id, 2000L, SELLER);
            sales.changeStatus(id, SalesStatus.ON_SALE, SELLER);
            String member = UUID.randomUUID().toString();
            var order =
                    creation.create(
                                    new CreateOrderCommand(
                                            productId, 1, "구매자", "01012345678", member, 2000L),
                                    "kafka")
                            .order();
            orders.cancelBeforePayment(order.getOrderNumber(), member);

            List<EventEnvelope<SalesInfoSnapshot>> events = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(20);
            while (events.size() < 5 && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofMillis(250))) {
                    EventEnvelope<SalesInfoSnapshot> event =
                            mapper.readValue(
                                    record.value(),
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            EventEnvelope<SalesInfoSnapshot>>() {});
                    if (event.payload().productId() != productId) continue;
                    assertThat(record.key()).isEqualTo(Long.toString(id));
                    assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                    assertThat(mapper.readTree(record.value()).get("occurredAt").isTextual())
                            .isTrue();
                    events.add(event);
                }
            }
            assertThat(events).hasSize(5);
            assertThat(events)
                    .extracting(EventEnvelope::eventType)
                    .containsExactly(
                            "SalesRegistered",
                            "PriceChanged",
                            "SalesStatusChanged",
                            "SalesStatusChanged",
                            "SalesStatusChanged");
            assertThat(events)
                    .extracting(e -> e.payload().snapshotVersion())
                    .containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(events.getLast().payload().available()).isEqualTo(1);
            assertThat(events.getLast().payload().status()).isEqualTo(SalesInfoStatus.ON_SALE);
            assertThat(events).extracting(EventEnvelope::eventId).doesNotHaveDuplicates();
        }
    }
}
