package com.shoppinglive.live.integration.member;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppinglive.live.integration.member.MemberProfileException.Reason;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Member 의 GET /v1/members/me. 호출자의 토큰만 전달하고 displayName 외의 필드는 버린다. */
public record HttpMemberProfileClient(RestClient client) implements MemberProfileClient {
    private static final int MAX_DISPLAY_NAME = 80;

    @Override
    public String displayName(final String authorization, final String memberId) {
        final JsonNode body;
        try {
            body = client.get().uri("/v1/members/me").header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new MemberProfileException(Reason.UNAUTHORIZED);
        } catch (RuntimeException e) {
            throw new MemberProfileException(Reason.UNAVAILABLE);
        }
        final JsonNode data = body == null ? null : body.path("data");
        final boolean trusted = data != null && body.path("success").asBoolean(false)
            && memberId.equals(data.path("memberId").asText(null)) && data.path("displayName").isTextual();
        if (!trusted) {
            throw new MemberProfileException(Reason.UNAVAILABLE);
        }
        final String displayName = data.path("displayName").asText();
        if (displayName.isBlank() || displayName.length() > MAX_DISPLAY_NAME) {
            throw new MemberProfileException(Reason.UNAVAILABLE);
        }
        return displayName;
    }
}
