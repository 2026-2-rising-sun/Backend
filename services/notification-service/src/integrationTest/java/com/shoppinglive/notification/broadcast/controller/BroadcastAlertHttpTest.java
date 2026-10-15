package com.shoppinglive.notification.broadcast.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.security.NotificationSecuritySupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=${NOTIFICATION_TEST_POSTGRES_URL:jdbc:h2:mem:broadcast_alert_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH}",
        })
@AutoConfigureMockMvc
@DisplayName("방송 시작 알림 신청 실제 HTTP 계약")
class BroadcastAlertHttpTest extends NotificationSecuritySupport {

    private static final long BROADCAST = 42L;
    private static final long UNKNOWN_BROADCAST = 999L;

    @Autowired MockMvc mvc;
    @Autowired BroadcastAlertRepository alerts;
    @Autowired BroadcastScheduleRepository schedules;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepareSchedule() {
        jdbc.update("DELETE FROM broadcast_alert");
        jdbc.update("DELETE FROM broadcast_schedule");
        schedules.apply(
                new BroadcastSchedule(
                        BROADCAST, 1, "가을 신상 라이브", Instant.parse("2026-10-20T12:00:00Z")));
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM broadcast_alert");
        jdbc.update("DELETE FROM broadcast_schedule");
    }

    @Test
    @DisplayName("같은 방송을 두 번 신청해도 성공하고 신청은 하나만 남는다")
    void registeringTwiceKeepsOneAlert() throws Exception {
        register(BROADCAST).andExpect(status().isCreated());
        register(BROADCAST).andExpect(status().isCreated());

        assertThat(alerts.exists(JwtTestTokens.MEMBER_A, BROADCAST)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_alert", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("신청한 적이 없는 방송을 해제해도 성공한다 — 원한 상태가 이미 달성돼 있다")
    void unregisteringWithoutRegistrationSucceeds() throws Exception {
        mvc.perform(
                        delete("/v1/broadcast-alerts/{id}", BROADCAST)
                                .header("Authorization", userBearer()))
                .andExpect(status().isNoContent());

        assertThat(alerts.exists(JwtTestTokens.MEMBER_A, BROADCAST)).isFalse();
    }

    @Test
    @DisplayName("신청한 뒤 해제하면 기록이 사라진다")
    void registerThenUnregister() throws Exception {
        register(BROADCAST).andExpect(status().isCreated());

        mvc.perform(
                        delete("/v1/broadcast-alerts/{id}", BROADCAST)
                                .header("Authorization", userBearer()))
                .andExpect(status().isNoContent());

        assertThat(alerts.exists(JwtTestTokens.MEMBER_A, BROADCAST)).isFalse();
    }

    @Test
    @DisplayName("일정을 받지 못한 방송은 404 — 신청을 받아 두고 못 보내는 것보다 알려주는 쪽이 맞다")
    void registeringUnknownBroadcastIsNotFound() throws Exception {
        register(UNKNOWN_BROADCAST)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(alerts.exists(JwtTestTokens.MEMBER_A, UNKNOWN_BROADCAST)).isFalse();
    }

    @Test
    @DisplayName("broadcastId 가 없거나 0 이하면 400")
    void rejectsInvalidBroadcastId() throws Exception {
        mvc.perform(
                        post("/v1/broadcast-alerts")
                                .header("Authorization", userBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());

        register(0).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("로그인하지 않으면 신청할 수 없다")
    void registrationRequiresLogin() throws Exception {
        mvc.perform(
                        post("/v1/broadcast-alerts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"broadcastId\":42}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions register(long broadcastId)
            throws Exception {
        return mvc.perform(
                post("/v1/broadcast-alerts")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broadcastId\":" + broadcastId + "}"));
    }
}
