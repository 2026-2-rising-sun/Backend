package com.shoppinglive.live.thumbnail;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BroadcastMetadataHttpTest extends LiveSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("썸네일 업로드와 공개 조회·방송 메타정보 저장·수정·타인 참조 거절을 검증한다")
    void uploadsAndPersistsPublicMetadataWithOwnership() throws Exception {
        var file =
                new MockMultipartFile(
                        "file",
                        "thumbnail.png",
                        "image/png",
                        BroadcastThumbnailValidationTest.png(10));
        mvc.perform(multipart("/v1/seller/broadcast-thumbnails").file(file))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        multipart("/v1/seller/broadcast-thumbnails")
                                .file(file)
                                .header("Authorization", userBearer()))
                .andExpect(status().isForbidden());
        var upload =
                mvc.perform(
                                multipart("/v1/seller/broadcast-thumbnails")
                                        .file(file)
                                        .header("Authorization", sellerBearer()))
                        .andExpect(status().isCreated())
                        .andReturn();
        String thumbnail =
                mapper.readTree(upload.getResponse().getContentAsString())
                        .path("data")
                        .path("thumbnailId")
                        .asText();
        mvc.perform(get("/v1/broadcast-thumbnails/" + thumbnail))
                .andExpect(status().isOk())
                .andExpect(content().bytes(file.getBytes()));
        var input =
                Map.of(
                        "title",
                        "메타정보 방송",
                        "scheduledAt",
                        Instant.now().plusSeconds(3600).toString(),
                        "channelArn",
                        "arn:metadata:" + UUID.randomUUID(),
                        "playbackUrl",
                        "https://test.example/live.m3u8",
                        "hostName",
                        "공개 진행자",
                        "description",
                        "방송 소개",
                        "thumbnailId",
                        thumbnail);
        var response =
                mvc.perform(
                                post("/v1/seller/broadcasts")
                                        .header("Authorization", sellerBearer())
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(input)))
                        .andExpect(status().isCreated())
                        .andReturn();
        var broadcast = mapper.readTree(response.getResponse().getContentAsString()).path("data");
        long id = broadcast.path("id").asLong(), version = broadcast.path("version").asLong();
        mvc.perform(get("/v1/broadcasts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hostName").value("공개 진행자"))
                .andExpect(jsonPath("$.data.description").value("방송 소개"))
                .andExpect(
                        jsonPath("$.data.thumbnailUrl")
                                .value("/api/live/v1/broadcast-thumbnails/" + thumbnail))
                .andExpect(jsonPath("$.data.channelArn").doesNotExist());
        String other = "Bearer " + TOKENS.token(UUID.randomUUID().toString(), Set.of("SELLER"));
        mvc.perform(
                        post("/v1/seller/broadcasts")
                                .header("Authorization", other)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(input)))
                .andExpect(status().isForbidden());
        var retained =
                mvc.perform(
                                patch("/v1/seller/broadcasts/" + id)
                                        .param("version", Long.toString(version))
                                        .header("Authorization", other)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "title",
                                                                "공동 관리 제목",
                                                                "thumbnailId",
                                                                thumbnail))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.title").value("공동 관리 제목"))
                        .andExpect(
                                jsonPath("$.data.thumbnailUrl")
                                        .value("/api/live/v1/broadcast-thumbnails/" + thumbnail))
                        .andReturn();
        version =
                mapper.readTree(retained.getResponse().getContentAsString())
                        .path("data")
                        .path("version")
                        .asLong();
        var otherUpload =
                mvc.perform(
                                multipart("/v1/seller/broadcast-thumbnails")
                                        .file(file)
                                        .header("Authorization", other))
                        .andExpect(status().isCreated())
                        .andReturn();
        String otherThumbnail =
                mapper.readTree(otherUpload.getResponse().getContentAsString())
                        .path("data")
                        .path("thumbnailId")
                        .asText();
        mvc.perform(
                        patch("/v1/seller/broadcasts/" + id)
                                .param("version", Long.toString(version))
                                .header("Authorization", sellerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("thumbnailId", otherThumbnail))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        patch("/v1/seller/broadcasts/" + id)
                                .param("version", Long.toString(version))
                                .header("Authorization", sellerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"hostName\":\"변경 진행자\",\"thumbnailId\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hostName").value("변경 진행자"))
                .andExpect(jsonPath("$.data.thumbnailUrl").isEmpty());
    }
}
