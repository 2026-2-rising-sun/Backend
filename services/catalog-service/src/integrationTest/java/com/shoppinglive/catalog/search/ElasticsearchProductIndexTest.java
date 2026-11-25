package com.shoppinglive.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Nori 플러그인이 있는 실제 ES 에서 확인한다. {@code CATALOG_TEST_ES_URL} 이 가리키는 ES 의 products_* 인덱스를 지우고 쓴다. */
@Tag("elasticsearch")
class ElasticsearchProductIndexTest {
    private RestClient rest;
    private ElasticsearchClient client;
    private ElasticsearchProductIndex index;

    @BeforeEach
    void prepare() throws IOException {
        rest = RestClient.builder(HttpHost.create(System.getenv("CATALOG_TEST_ES_URL"))).build();
        client = new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
        deleteProductIndices();
        index = new ElasticsearchProductIndex(client, Clock.systemUTC());
    }

    @AfterEach
    void close() throws IOException {
        deleteProductIndices();
        rest.close();
    }

    /** ES 8 은 와일드카드 삭제를 막으므로 이름을 조회해 지운다. */
    private void deleteProductIndices() throws IOException {
        var names =
                client.indices()
                        .get(g -> g.index("products_*").allowNoIndices(true))
                        .result()
                        .keySet();
        if (!names.isEmpty()) client.indices().delete(d -> d.index(List.copyOf(names)));
    }

    private ProductSearchDocument document(long id, String name, boolean soldOut) {
        return new ProductSearchDocument(
                id,
                name,
                "가볍고 편한 신발",
                "FASHION",
                null,
                15000,
                soldOut,
                Instant.parse("2026-11-24T00:00:00Z"));
    }

    private List<Long> search(String query) throws IOException {
        client.indices().refresh(r -> r.index(ElasticsearchProductIndex.ALIAS));
        return client
                .search(
                        s ->
                                s.index(ElasticsearchProductIndex.ALIAS)
                                        .query(q -> q.match(m -> m.field("name").query(query))),
                        Map.class)
                .hits()
                .hits()
                .stream()
                .map(hit -> Long.parseLong(hit.id()))
                .toList();
    }

    @Test
    @DisplayName("alias가 없으면 부분 색인은 쓰지 않고, 전체 색인이 공개한 뒤에는 조사가 붙은 검색어로도 상품명을 찾는다")
    void writesOnlyAfterPublishAndSearchesKorean() throws IOException {
        assertThatThrownBy(() -> index.write(List.of(document(1, "남성 러닝 운동화", false)), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(client.indices().exists(e -> e.index("products")).value()).isFalse();

        index.publish(index.createIndex(), 0);
        Set<Long> failed = index.write(List.of(document(1, "남성 러닝 운동화", false)), List.of());

        assertThat(failed).isEmpty();
        assertThat(search("운동화를")).containsExactly(1L);
    }

    @Test
    @DisplayName("같은 상품을 다시 쓰면 문서 하나를 고치고, 삭제와 없는 문서 삭제는 성공한다")
    void upsertsAndDeletes() throws IOException {
        index.publish(index.createIndex(), 0);
        index.write(List.of(document(2, "겨울 니트", false)), List.of());
        index.write(List.of(document(2, "겨울 가디건", true)), List.of());

        assertThat(search("가디건")).containsExactly(2L);
        assertThat(search("니트")).isEmpty();

        assertThat(index.write(List.of(), List.of(2L, 999L))).isEmpty();
        assertThat(search("가디건")).isEmpty();
    }
}
