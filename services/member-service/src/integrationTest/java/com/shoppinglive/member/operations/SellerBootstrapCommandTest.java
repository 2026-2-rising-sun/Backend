package com.shoppinglive.member.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.member.MemberServiceApplication;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServerFactory;

class SellerBootstrapCommandTest {
    @Test
    @DisplayName("판매자 생성 명령은 HTTP 서버나 회원 앱 전체 스캔 없이 실행된다")
    void commandContextHasNoHttpServerOrMemberApplicationScan() {
        try (var context = SellerBootstrapCommand.openNonWebContext()) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(WebServerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(MemberServiceApplication.class)).isEmpty();
            assertThat(context.getBean(SellerBootstrapCommand.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("설정 누락과 잘못된 입력은 비밀 값을 노출하지 않고 거절한다")
    void rejectsMissingSettingsAndInvalidInputWithoutExposingSecrets() {
        var command = new SellerBootstrapCommand();
        assertThatThrownBy(() -> command.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var environment =
                new HashMap<>(
                        Map.of(
                                "MEMBER_BOOTSTRAP_DB_URL",
                                "jdbc:postgresql://unused/test",
                                "MEMBER_BOOTSTRAP_DB_SCHEMA",
                                "public",
                                "MEMBER_BOOTSTRAP_DB_USER",
                                "operator",
                                "MEMBER_BOOTSTRAP_DB_PASSWORD",
                                "database-secret",
                                "MEMBER_BOOTSTRAP_SELLER_EMAIL",
                                "bad-email",
                                "MEMBER_BOOTSTRAP_SELLER_PASSWORD",
                                "secret-password",
                                "MEMBER_BOOTSTRAP_SELLER_DISPLAY_NAME",
                                "admin"));
        assertThatThrownBy(() -> command.execute(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매자 이메일·표시 이름·비밀번호가 올바르지 않습니다.");
        assertThatThrownBy(
                        () ->
                                MemberServiceApplication.main(
                                        new String[] {"--bootstrap-seller", "unexpected"}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
