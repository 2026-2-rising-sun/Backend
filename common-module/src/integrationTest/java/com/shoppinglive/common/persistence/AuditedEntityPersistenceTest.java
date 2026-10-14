package com.shoppinglive.common.persistence;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.Entity;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = AuditedEntityPersistenceTest.Configuration.class)
class AuditedEntityPersistenceTest {
    @Autowired TestEntityManager em;
    @Autowired AtomicReference<Instant> time;

    @Test
    void 자동_ID와_시간을_상속하고_수정시_생성시간은_유지한다() {
        Instant created = Instant.parse("2026-10-14T00:00:00Z");
        time.set(created);
        var entity = new AuditedFixture();
        entity.name = "before";
        em.persistAndFlush(entity);
        Long id = entity.getId();
        assertThat(id).isPositive();
        assertThat(entity.getCreatedAt()).isEqualTo(created);
        assertThat(entity.getUpdatedAt()).isEqualTo(created);
        em.clear();
        var loaded = em.find(AuditedFixture.class, id);
        Instant modified = created.plusSeconds(10);
        time.set(modified);
        loaded.name = "after";
        em.flush();
        em.clear();
        loaded = em.find(AuditedFixture.class, id);
        assertThat(loaded.getCreatedAt()).isEqualTo(created);
        assertThat(loaded.getUpdatedAt()).isEqualTo(modified);
        assertThat(
                        em.getEntityManager()
                                .createQuery(
                                        "select e from AuditedFixture e order by e.createdAt",
                                        AuditedFixture.class)
                                .getSingleResult()
                                .getId())
                .isEqualTo(id);
    }

    @Entity(name = "AuditedFixture")
    static class AuditedFixture extends BaseEntity {
        String name;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AuditedEntityPersistenceTest.class)
    @EnableJpaAuditing(dateTimeProviderRef = "auditingTime")
    static class Configuration {
        @Bean
        AtomicReference<Instant> time() {
            return new AtomicReference<>();
        }

        @Bean
        DateTimeProvider auditingTime(AtomicReference<Instant> time) {
            return () -> Optional.ofNullable(time.get());
        }
    }
}
