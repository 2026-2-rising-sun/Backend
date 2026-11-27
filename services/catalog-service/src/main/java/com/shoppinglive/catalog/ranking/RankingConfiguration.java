package com.shoppinglive.catalog.ranking;

import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 조회수 랭킹. 검색 색인을 꺼도 조회수와 점수는 계속 쌓는다. 점수가 바뀐 상품은 index_task 로 남으므로 색인을 켜면 반영된다.
 *
 * <p>잠금 실행기는 빈으로 등록하지 않는다. 검색 색인 설정의 잠금 실행기와 타입이 같아 주입이 모호해지지 않게 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RankingConfiguration {

    @Bean
    ProductViewCounter productViewCounter(NamedParameterJdbcTemplate jdbc) {
        return new ProductViewCounter(jdbc, Clock.systemUTC());
    }

    @Bean
    ViewRanking viewRanking(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            DataSource dataSource,
            @Value("${catalog.ranking.window:7d}") Duration window) {
        return new ViewRanking(
                jdbc,
                new TransactionTemplate(transactions),
                new DefaultLockingTaskExecutor(
                        new JdbcTemplateLockProvider(
                                JdbcTemplateLockProvider.Configuration.builder()
                                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                                        .usingDbTime()
                                        .build())),
                Clock.systemUTC(),
                window);
    }
}
