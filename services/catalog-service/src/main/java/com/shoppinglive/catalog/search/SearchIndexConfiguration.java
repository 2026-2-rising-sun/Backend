package com.shoppinglive.catalog.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.CronExpression;

/**
 * 검색 색인은 {@code catalog.search.enabled=true} 일 때만 켠다. 꺼져 있어도 index_task 는 계속 쌓이므로, ES 를 붙인 뒤 켜면 밀린
 * 상품부터 반영된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "catalog.search", name = "enabled", havingValue = "true")
class SearchIndexConfiguration {

    /** 잠금 시각은 DB 시계로 잰다. Pod 마다 시계가 달라도 잠금이 겹치지 않는다. */
    @Bean
    LockingTaskExecutor searchIndexLocks(DataSource dataSource) {
        return new DefaultLockingTaskExecutor(
                new JdbcTemplateLockProvider(
                        JdbcTemplateLockProvider.Configuration.builder()
                                .withJdbcTemplate(new JdbcTemplate(dataSource))
                                .usingDbTime()
                                .build()));
    }

    @Bean
    ElasticsearchProductIndex productSearchIndex(ElasticsearchClient client) {
        return new ElasticsearchProductIndex(client, Clock.systemUTC());
    }

    @Bean
    PartialIndexer partialIndexer(
            NamedParameterJdbcTemplate jdbc,
            ProductSearchIndex index,
            LockingTaskExecutor searchIndexLocks,
            @Value("${catalog.search.partial.batch-size:500}") int batchSize,
            @Value("${catalog.search.partial.lock-at-most-for:1m}") Duration lockAtMostFor) {
        return new PartialIndexer(
                jdbc,
                new ProductSearchSource(jdbc),
                index,
                searchIndexLocks,
                batchSize,
                lockAtMostFor);
    }

    @Bean
    FullIndexer fullIndexer(
            NamedParameterJdbcTemplate jdbc,
            ElasticsearchProductIndex index,
            LockingTaskExecutor searchIndexLocks,
            @Value("${catalog.search.full.cron:0 0 4 * * *}") String cron,
            @Value("${catalog.search.full.zone:Asia/Seoul}") String zone,
            @Value("${catalog.search.full.batch-size:500}") int batchSize,
            @Value("${catalog.search.full.lock-at-most-for:30m}") Duration lockAtMostFor) {
        return new FullIndexer(
                jdbc,
                new ProductSearchSource(jdbc),
                index,
                searchIndexLocks,
                CronExpression.parse(cron),
                ZoneId.of(zone),
                Clock.systemUTC(),
                batchSize,
                lockAtMostFor);
    }
}
