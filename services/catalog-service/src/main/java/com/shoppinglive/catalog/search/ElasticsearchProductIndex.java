package com.shoppinglive.catalog.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * alias {@value #ALIAS} 로 읽고 쓴다. 실제 인덱스는 {@code products_시각} 이고, 전체 색인이 새 인덱스를 만든 뒤 alias 를 옮긴다.
 *
 * <p>alias 가 아직 없으면 같은 매핑으로 첫 인덱스를 만들어 alias 를 붙인다. alias 없이 쓰면 ES 가 같은 이름의 일반 인덱스를 자동으로 만들어 이후
 * alias 교체를 막기 때문이다.
 */
public class ElasticsearchProductIndex implements ProductSearchIndex {
    public static final String ALIAS = "products";
    private static final DateTimeFormatter SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final ElasticsearchClient client;
    private final Clock clock;

    public ElasticsearchProductIndex(ElasticsearchClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public Set<Long> write(Collection<ProductSearchDocument> upserts, Collection<Long> deletes) {
        if (upserts.isEmpty() && deletes.isEmpty()) return Set.of();
        try {
            ensureAlias();
            BulkRequest.Builder bulk = new BulkRequest.Builder().index(ALIAS);
            for (ProductSearchDocument document : upserts)
                bulk.operations(
                        op ->
                                op.index(
                                        i ->
                                                i.id(String.valueOf(document.productId()))
                                                        .document(document.source())));
            for (long id : deletes) bulk.operations(op -> op.delete(d -> d.id(String.valueOf(id))));
            Set<Long> failed = new HashSet<>();
            // 없는 문서 삭제는 error 없이 not_found 로 온다.
            for (BulkResponseItem item : client.bulk(bulk.build()).items())
                if (item.error() != null) failed.add(Long.parseLong(item.id()));
            return failed;
        } catch (IOException failure) {
            throw new UncheckedIOException("검색 색인에 반영하지 못했습니다.", failure);
        }
    }

    private void ensureAlias() throws IOException {
        if (client.indices().existsAlias(a -> a.name(ALIAS)).value()) return;
        String name = ALIAS + "_" + SUFFIX.format(clock.instant());
        try (InputStream mapping = getClass().getResourceAsStream("/elasticsearch/products.json")) {
            if (mapping == null) throw new IllegalStateException("검색 인덱스 매핑 파일이 없습니다.");
            client.indices().create(c -> c.index(name).withJson(mapping).aliases(ALIAS, a -> a));
        }
    }
}
