package com.shoppinglive.catalog.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * alias {@value #ALIAS} 로 읽고 쓴다. 실제 인덱스는 {@code products_시각} 이고, 전체 색인이 새 인덱스를 채운 뒤 alias 를 옮긴다.
 *
 * <p>부분 색인은 alias 가 있을 때만 쓴다. alias 없이 쓰면 ES 가 같은 이름의 일반 인덱스를 자동으로 만들어 이후 alias 교체를 막기 때문이다. 첫 인덱스는
 * 전체 색인이 만든다.
 */
public class ElasticsearchProductIndex implements ProductSearchIndex {
    public static final String ALIAS = "products";
    private static final String PREFIX = ALIAS + "_";
    private static final DateTimeFormatter SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ElasticsearchClient client;
    private final Clock clock;

    public ElasticsearchProductIndex(ElasticsearchClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public Set<Long> write(Collection<ProductSearchDocument> upserts, Collection<Long> deletes) {
        if (upserts.isEmpty() && deletes.isEmpty()) return Set.of();
        if (currentIndex().isEmpty())
            throw new IllegalStateException("검색 인덱스가 아직 없습니다. 전체 색인이 만든 뒤 반영합니다.");
        return bulk(ALIAS, upserts, deletes);
    }

    /** alias 가 가리키는 인덱스. 비어 있으면 전체 색인이 아직 한 번도 끝나지 않은 것이다. */
    public Optional<String> currentIndex() {
        try {
            if (!client.indices().existsAlias(a -> a.name(ALIAS)).value()) return Optional.empty();
            return client.indices().getAlias(a -> a.name(ALIAS)).result().keySet().stream()
                    .findFirst();
        } catch (IOException failure) {
            throw new UncheckedIOException("검색 인덱스를 확인하지 못했습니다.", failure);
        }
    }

    /** 인덱스 이름의 시각. 전체 색인이 그 인덱스를 만들기 시작한 시각이다. */
    public static Instant createdAt(String index) {
        return Instant.from(SUFFIX.parse(index.substring(PREFIX.length())));
    }

    /** alias 없이 빈 인덱스를 만든다. 전체 색인이 채운 뒤 {@link #publish} 로 공개한다. */
    public String createIndex() {
        String name = PREFIX + SUFFIX.format(clock.instant());
        try (InputStream mapping = getClass().getResourceAsStream("/elasticsearch/products.json")) {
            if (mapping == null) throw new IllegalStateException("검색 인덱스 매핑 파일이 없습니다.");
            client.indices().create(c -> c.index(name).withJson(mapping));
            return name;
        } catch (IOException failure) {
            throw new UncheckedIOException("검색 인덱스를 만들지 못했습니다.", failure);
        }
    }

    /**
     * @return 반영하지 못한 상품 id
     */
    public Set<Long> add(String index, Collection<ProductSearchDocument> documents) {
        if (documents.isEmpty()) return Set.of();
        return bulk(index, documents, List.of());
    }

    /**
     * 문서 수가 맞으면 alias 를 새 인덱스로 한 번에 옮긴다. 직전 인덱스는 되돌리기용으로 남기고 그보다 오래된 인덱스는 지운다.
     *
     * @throws IllegalStateException 문서 수가 다르면 alias 를 옮기지 않는다
     */
    public void publish(String index, long expectedDocuments) {
        try {
            client.indices().refresh(r -> r.index(index));
            long count = client.count(c -> c.index(index)).count();
            if (count != expectedDocuments)
                throw new IllegalStateException(
                        "전체 색인 문서 수가 다릅니다: 기대=" + expectedDocuments + ", 실제=" + count);
            Optional<String> previous = currentIndex();
            List<Action> actions = new ArrayList<>();
            previous.ifPresent(
                    p -> actions.add(Action.of(a -> a.remove(r -> r.index(p).alias(ALIAS)))));
            actions.add(Action.of(a -> a.add(n -> n.index(index).alias(ALIAS))));
            client.indices().updateAliases(u -> u.actions(actions));
            List<String> old =
                    indexNames().stream()
                            .filter(name -> !name.equals(index))
                            .filter(name -> previous.map(p -> !p.equals(name)).orElse(true))
                            .toList();
            if (!old.isEmpty()) client.indices().delete(d -> d.index(old));
        } catch (IOException failure) {
            throw new UncheckedIOException("검색 인덱스를 공개하지 못했습니다.", failure);
        }
    }

    /** 실패한 전체 색인의 인덱스를 지운다. alias 가 가리키지 않는 인덱스여야 한다. */
    public void delete(String index) {
        try {
            client.indices().delete(d -> d.index(index).ignoreUnavailable(true));
        } catch (IOException failure) {
            throw new UncheckedIOException("검색 인덱스를 지우지 못했습니다.", failure);
        }
    }

    List<String> indexNames() throws IOException {
        return List.copyOf(
                client.indices()
                        .get(g -> g.index(PREFIX + "*").allowNoIndices(true))
                        .result()
                        .keySet());
    }

    private Set<Long> bulk(
            String target, Collection<ProductSearchDocument> upserts, Collection<Long> deletes) {
        BulkRequest.Builder bulk = new BulkRequest.Builder().index(target);
        for (ProductSearchDocument document : upserts)
            bulk.operations(
                    op ->
                            op.index(
                                    i ->
                                            i.id(String.valueOf(document.productId()))
                                                    .document(document.source())));
        for (long id : deletes) bulk.operations(op -> op.delete(d -> d.id(String.valueOf(id))));
        try {
            Set<Long> failed = new HashSet<>();
            // 없는 문서 삭제는 error 없이 not_found 로 온다.
            for (BulkResponseItem item : client.bulk(bulk.build()).items())
                if (item.error() != null) failed.add(Long.parseLong(item.id()));
            return failed;
        } catch (IOException failure) {
            throw new UncheckedIOException("검색 색인에 반영하지 못했습니다.", failure);
        }
    }
}
