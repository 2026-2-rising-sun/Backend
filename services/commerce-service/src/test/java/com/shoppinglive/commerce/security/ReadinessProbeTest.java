package com.shoppinglive.commerce.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** A dedicated pool is closed here; shared transaction test contexts remain untouched. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:commerce_probe;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessProbeTest extends CommerceSecurityTestSupport {
    @Override protected boolean shouldCleanPurchaseFixtures() { return false; }

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @Test
    void unavailableDataSourceFailsReadinessButNotLivenessWithoutExposingDetails() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        dataSource.unwrap(HikariDataSource.class).close();
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.details").doesNotExist())
            .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
    }
}
