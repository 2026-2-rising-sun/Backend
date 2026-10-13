package com.shoppinglive.notification.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.notification.inbox.domain.Notification;
import com.shoppinglive.notification.inbox.infrastructure.NotificationRepository;
import com.shoppinglive.notification.security.NotificationSecuritySupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:notification_inbox;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
})
@AutoConfigureMockMvc
@DisplayName("알림함 실제 HTTP 핵심 계약")
class NotificationInboxHttpTest extends NotificationSecuritySupport {

    private static final Instant BASE = Instant.parse("2026-10-12T00:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    NotificationRepository notifications;

    private long myOldest;
    private long othersNotification;

    @BeforeEach
    void prepareInbox() {
        // 같은 시각으로 2건을 만든다. 방송 시작 알림처럼 한꺼번에 생기는 경우를 커서가 견디는지 보기 위해서다.
        myOldest = save(JwtTestTokens.MEMBER_A, BASE).getId();
        save(JwtTestTokens.MEMBER_A, BASE.plusSeconds(60));
        save(JwtTestTokens.MEMBER_A, BASE.plusSeconds(60));
        othersNotification = save(JwtTestTokens.MEMBER_B, BASE.plusSeconds(120)).getId();
    }

    @AfterEach
    void cleanFixtures() {
        notifications.deleteAll();
    }

    @Test
    @DisplayName("내 알림만 최신순으로 주고, 커서로 다음 구간을 중복·누락 없이 준다")
    void listReturnsMyNotificationsInOrderWithoutOverlap() throws Exception {
        JsonNode first = list("?size=2");

        assertThat(first.path("items")).hasSize(2);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(first.path("items").get(0).path("title").asText()).isEqualTo("결제가 완료되었습니다");

        JsonNode second = list("?size=2&cursor=" + first.path("nextCursor").asText());

        // 남의 알림(evt-4)은 더 최신이지만 들어오지 않고, 같은 시각 2건도 겹치지 않고 나뉘어야 한다.
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        assertThat(second.path("items").get(0).path("id").asLong()).isEqualTo(myOldest);
    }

    @Test
    @DisplayName("안 읽은 개수는 읽음 처리로 줄고, 같은 알림을 두 번 읽어도 성공한다")
    void unreadCountDecreasesAndReadIsIdempotent() throws Exception {
        assertThat(unreadCount()).isEqualTo(3);

        markRead(myOldest);
        Instant firstReadAt = notifications.findById(myOldest).orElseThrow().getReadAt();
        markRead(myOldest);

        assertThat(unreadCount()).isEqualTo(2);
        // 두 번째 호출이 읽은 시각을 덮어쓰면 "언제 읽었나" 가 사용자의 실제 행동과 달라진다.
        assertThat(notifications.findById(myOldest).orElseThrow().getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("남의 알림을 읽음 처리하면 404 — 권한 오류를 주면 존재 여부가 새어나간다")
    void readingSomeoneElsesNotificationIsNotFound() throws Exception {
        mvc.perform(post("/v1/notifications/{id}/read", othersNotification)
                .header("Authorization", userBearer()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(notifications.findById(othersNotification).orElseThrow().getReadAt()).isNull();
    }

    @Test
    @DisplayName("로그인하지 않으면 알림함에 접근할 수 없다")
    void inboxRequiresLogin() throws Exception {
        mvc.perform(get("/v1/notifications")).andExpect(status().isUnauthorized());
    }

    private Notification save(String memberId, Instant occurredAt) {
        return notifications.saveAndFlush(new Notification(memberId, "결제가 완료되었습니다",
            "가을 니트 외 2건 · 89,000원", occurredAt));
    }

    private JsonNode list(String query) throws Exception {
        String response = mvc.perform(get("/v1/notifications" + query).header("Authorization", userBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }

    private long unreadCount() throws Exception {
        String response = mvc.perform(get("/v1/notifications/unread-count")
                .header("Authorization", userBearer()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("unreadCount").asLong();
    }

    private void markRead(long notificationId) throws Exception {
        mvc.perform(post("/v1/notifications/{id}/read", notificationId).header("Authorization", userBearer()))
            .andExpect(status().isNoContent());
    }
}
