package com.shoppinglive.live.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.api.BroadcastPatchInput;
import java.util.Optional;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("방송 등록·수정 (멱등성, 낙관적 잠금, HTTP 계약)")
class BroadcastRegistrationTest {
    @Autowired BroadcastService service;
    @Autowired BroadcastRepository repository;
    @Autowired MockMvc mvc;

    private final Instant past = Instant.parse("2020-01-01T00:00:00Z");
    private final String channelArn = "arn:aws:ivs:ap-northeast-2:123456789012:channel/abc";
    private final String playbackUrl = "https://abc.live-video.net/api/channel.m3u8";

    @DisplayName("유효한 입력으로 등록하면 PREPARING 상태의 방송이 생성된다")
    @Test
    void registrationWithValidInputCreatesBroadcast() {
        final BroadcastInput input = new BroadcastInput("title", past, channelArn, playbackUrl);
        final Broadcast broadcast = service.register("reg-1", input);
        assertThat(broadcast.getId()).isNotNull();
        assertThat(broadcast.getTitle()).isEqualTo("title");
        assertThat(broadcast.getStatus().name()).isEqualTo("PREPARING");
    }

    @DisplayName("과거 시각으로 예약해도 등록이 허용된다")
    @Test
    void pastScheduledAtIsAllowed() {
        final BroadcastInput input = new BroadcastInput("past-scheduled", past, channelArn,
            playbackUrl);
        final Broadcast broadcast = service.register("past-sched", input);
        assertThat(broadcast.getScheduledAt()).isEqualTo(past);
    }

    @DisplayName("같은 Idempotency-Key로 재요청하면 동일한 방송을 돌려준다")
    @Test
    void idempotencyKeyReturnsIdenticalBroadcastOnRepeatedRequest() {
        final BroadcastInput input = new BroadcastInput("title", past, channelArn, playbackUrl);
        final Broadcast first = service.register("idem", input);
        final Broadcast second = service.register("idem", input);
        assertThat(first.getId()).isEqualTo(second.getId());
    }

    @DisplayName("같은 Idempotency-Key에 다른 본문을 보내면 409로 거절한다")
    @Test
    void differentPayloadWithSameKeyThrows409() {
        final BroadcastInput input1 = new BroadcastInput("title1", past, channelArn, playbackUrl);
        service.register("conflict", input1);
        final BroadcastInput input2 = new BroadcastInput("title2", past, channelArn, playbackUrl);
        assertThatThrownBy(() -> service.register("conflict", input2))
            .hasMessageContaining("다른 요청");
    }

    @DisplayName("같은 키로 동시에 등록해도 하나의 방송만 만들어진다")
    @Test
    void concurrentRegistrationWithSameKeyIsIdempotent() throws Exception {
        final BroadcastInput input = new BroadcastInput("title", past, channelArn, playbackUrl);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final CountDownLatch start = new CountDownLatch(1);
            final Callable<Long> register = () -> {
                start.await();
                return service.register("concurrent", input).getId();
            };
            final Future<Long> first = pool.submit(register);
            final Future<Long> second = pool.submit(register);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS))
                .isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
    }

    @DisplayName("수정하면 필드가 갱신되고 version이 올라간다")
    @Test
    void editingUpdatesFieldsAndIncreasesVersion() {
        final BroadcastInput input = new BroadcastInput("original", past, channelArn, playbackUrl);
        final Broadcast registered = service.register("edit-1", input);
        final long originalVersion = registered.getVersion();

        final BroadcastPatchInput patch = new BroadcastPatchInput(Optional.of("updated"),
            Optional.of(past), Optional.of(channelArn), Optional.of(playbackUrl));
        final Broadcast edited = service.edit(registered.getId(), originalVersion, patch);

        assertThat(edited.getTitle()).isEqualTo("updated");
        assertThat(edited.getVersion()).isGreaterThan(originalVersion);
    }

    @DisplayName("잘못된 version으로 수정하면 409로 거절한다")
    @Test
    void editingWithWrongVersionThrows409() {
        final BroadcastInput input = new BroadcastInput("title", past, channelArn, playbackUrl);
        final Broadcast registered = service.register("edit-2", input);
        final BroadcastPatchInput patch = new BroadcastPatchInput(Optional.of("title2"),
            Optional.of(past), Optional.of(channelArn), Optional.of(playbackUrl));
        assertThatThrownBy(() -> service.edit(registered.getId(), registered.getVersion() + 1,
            patch)).hasMessageContaining("변경되었습니다");
    }

    @DisplayName("Idempotency-Key로 POST하면 방송이 등록된다")
    @Test
    void httpPostWithIdempotencyKeyCreatesRegistration() throws Exception {
        final String body = String.format(
            """
            {"title":"http-title","scheduledAt":"%s","channelArn":"%s","playbackUrl":"%s"}
            """, past, channelArn, playbackUrl);
        mvc.perform(post("/v1/admin/broadcasts")
            .header("Idempotency-Key", "http-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("PREPARING"))
            .andExpect(jsonPath("$.data.title").value("http-title"));
    }

    @DisplayName("Idempotency-Key 없이 POST하면 400을 반환한다")
    @Test
    void httpPostWithoutIdempotencyKeyReturnsBadRequest() throws Exception {
        final String body = String.format(
            """
            {"title":"no-key","scheduledAt":"%s","channelArn":"%s","playbackUrl":"%s"}
            """, past, channelArn, playbackUrl);
        mvc.perform(post("/v1/admin/broadcasts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(status().isBadRequest());
    }

    @DisplayName("특수문자가 섞인 Idempotency-Key도 허용된다")
    @Test
    void httpPostWithSpecialCharIdempotencyKeyIsAccepted() throws Exception {
        final String body = String.format(
            """
            {"title":"special-key","scheduledAt":"%s","channelArn":"%s","playbackUrl":"%s"}
            """, past, channelArn, playbackUrl);
        mvc.perform(post("/v1/admin/broadcasts")
            .header("Idempotency-Key", "invalid key!")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(status().isCreated());
    }

    @DisplayName("128자를 넘는 Idempotency-Key는 400을 반환한다")
    @Test
    void httpPostWithTooLongIdempotencyKeyReturnsBadRequest() throws Exception {
        final String body = String.format(
            """
            {"title":"too-long-key","scheduledAt":"%s","channelArn":"%s","playbackUrl":"%s"}
            """, past, channelArn, playbackUrl);
        mvc.perform(post("/v1/admin/broadcasts")
            .header("Idempotency-Key", "k".repeat(129))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(status().isBadRequest());
    }

    @DisplayName("필수 필드가 빠진 POST는 400을 반환한다")
    @Test
    void httpPostWithoutRequiredFieldReturnsBadRequest() throws Exception {
        final String bodyNoTitle = String.format(
            """
            {"scheduledAt":"%s","channelArn":"%s","playbackUrl":"%s"}
            """, past, channelArn, playbackUrl);
        mvc.perform(post("/v1/admin/broadcasts")
            .header("Idempotency-Key", "no-title")
            .contentType(MediaType.APPLICATION_JSON)
            .content(bodyNoTitle))
            .andExpect(status().isBadRequest());
    }

    @DisplayName("관리 응답은 IVS 필드를 노출하되 비밀 필드는 숨긴다")
    @Test
    void adminResponseExposesIvsFieldsForOperatorVerification() throws Exception {
        // 관리 응답은 운영자가 채널/재생 URL 일치를 확인해야 하므로 IVS 필드를 포함한다.
        // 비밀 필드(requestKey/fingerprint)는 어떤 응답에도 넣지 않는다.
        final String body = String.format(
            """
            {"title":"admin-arn","scheduledAt":"%s","channelArn":"%s","playbackUrl":"%s"}
            """, past, channelArn, playbackUrl);
        mvc.perform(post("/v1/admin/broadcasts")
            .header("Idempotency-Key", "admin-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.channelArn").value(channelArn))
            .andExpect(jsonPath("$.data.requestKey").doesNotExist())
            .andExpect(jsonPath("$.data.fingerprint").doesNotExist());
    }

    @DisplayName("PATCH에서 생략한 필드는 기존 값을 유지한다")
    @Test
    void omittedPatchFieldsKeepCurrentValues() {
        final Broadcast registered = service.register("patch-keep",
            new BroadcastInput("keep", past, channelArn, playbackUrl));
        final Broadcast edited = service.edit(registered.getId(), registered.getVersion(),
            new BroadcastPatchInput(Optional.of("renamed"), null, null, null));
        assertThat(edited.getTitle()).isEqualTo("renamed");
        assertThat(edited.getScheduledAt()).isEqualTo(past);
        assertThat(edited.getChannelArn()).isEqualTo(channelArn);
        assertThat(edited.getPlaybackUrl()).isEqualTo(playbackUrl);
    }

    @DisplayName("PATCH로 필드를 명시적 null로 지정하면 거절한다")
    @Test
    void explicitNullPatchFieldIsRejected() {
        final Broadcast registered = service.register("patch-null",
            new BroadcastInput("null-test", past, channelArn, playbackUrl));
        assertThatThrownBy(() -> service.edit(registered.getId(), registered.getVersion(),
            new BroadcastPatchInput(Optional.empty(), null, null, null)))
            .hasMessageContaining("null을 지정할 수 없습니다");
    }

    @DisplayName("IVS 필드는 channelArn과 playbackUrl을 함께 변경해야 한다")
    @Test
    void ivsFieldsMustBePatchedAsAPair() {
        final Broadcast registered = service.register("patch-pair",
            new BroadcastInput("pair", past, channelArn, playbackUrl));
        assertThatThrownBy(() -> service.edit(registered.getId(), registered.getVersion(),
            new BroadcastPatchInput(null, null, Optional.of("arn:other"), null)))
            .hasMessageContaining("함께 변경");
    }

    @DisplayName("version과 함께 PATCH하면 필드가 수정된다")
    @Test
    void httpPatchWithValidInputUpdatesFields() throws Exception {
        final BroadcastInput input = new BroadcastInput("patch-test", past, channelArn,
            playbackUrl);
        final Broadcast broadcast = service.register("patch-1", input);
        final long version = broadcast.getVersion();

        final Instant newTime = Instant.parse("2021-01-01T00:00:00Z");
        final String patchBody = String.format(
            """
            {"title":"patched","scheduledAt":"%s","channelArn":"%s","playbackUrl":"%s"}
            """, newTime, channelArn, playbackUrl);

        mvc.perform(patch("/v1/admin/broadcasts/{id}", broadcast.getId())
            .param("version", String.valueOf(version))
            .contentType(MediaType.APPLICATION_JSON)
            .content(patchBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("patched"));
    }

    @DisplayName("version 파라미터 없이 PATCH하면 400을 반환한다")
    @Test
    void httpPatchWithoutVersionParamReturnsBadRequest() throws Exception {
        final BroadcastInput input = new BroadcastInput("no-version", past, channelArn,
            playbackUrl);
        final Broadcast broadcast = service.register("patch-2", input);

        final String patchBody = String.format(
            """
            {"title":"patched","scheduledAt":"%s","channelArn":"%s","playbackUrl":"%s"}
            """, past, channelArn, playbackUrl);

        mvc.perform(patch("/v1/admin/broadcasts/{id}", broadcast.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(patchBody))
            .andExpect(status().isBadRequest());
    }
}
