package com.shoppinglive.common.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class LocalApplicationTest {
    @TempDir Path directory;

    @Test
    @DisplayName("배포 및 테스트 프로필에서는 로컬 인증 파일을 읽지 않는다")
    void explicitDeploymentAndTestProfilesNeverReadLocalCredentials() {
        for (String profile : new String[] {"dev", "prod", "test", "local", "production"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            environment.setProperty("shoppinglive.local.credentials", "/does/not/exist");
            assertThatCode(() -> LocalApplication.prepare(environment)).doesNotThrowAnyException();
            assertThat(environment.getPropertySources().contains("shoppingliveLocalCredentials"))
                    .isFalse();
        }
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("local", "dev");
        assertThatCode(() -> LocalApplication.prepare(mixed)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("로컬 인증 값은 명시적 설정을 덮어쓰지 않고 기본값으로 적용한다")
    void localCredentialsAreSharedFallbacksWithoutOverwritingExplicitValues() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key :
                new String[] {
                    "MEMBER_JWT_PUBLIC_KEY_SET_LOCATION",
                    "MEMBER_JWT_PRIVATE_KEY_LOCATION",
                    "MEMBER_JWT_KEY_ID",
                    "MEMBER_ACCESS_TOKEN_TTL",
                    "MEMBER_REFRESH_TOKEN_TTL",
                    "MEMBER_SESSION_BASE_URL",
                    "SHOPPING_MEMBER_SERVICE_TOKEN",
                    "COMMERCE_MEMBER_SERVICE_TOKEN",
                    "LIVE_MEMBER_SERVICE_TOKEN",
                    "COMMERCE_SHOPPING_SERVICE_TOKEN",
                    "LIVE_SHOPPING_SERVICE_TOKEN",
                    "SHOPPING_COMMERCE_SERVICE_TOKEN",
                    "LIVE_COMMERCE_SERVICE_TOKEN"
                }) {
            values.put(key, "generated");
        }
        Path file = directory.resolve("env.json");
        Files.writeString(
                file,
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "purpose",
                                        "isolated local development only",
                                        "env",
                                        values)));
        MockEnvironment environment = local(file);
        environment.setProperty("LIVE_MEMBER_SERVICE_TOKEN", "explicit");

        LocalApplication.prepare(environment);

        assertThat(environment.getProperty("MEMBER_JWT_KEY_ID")).isEqualTo("generated");
        assertThat(environment.getProperty("LIVE_MEMBER_SERVICE_TOKEN")).isEqualTo("explicit");
    }

    @Test
    @DisplayName("인증 파일이 없거나 잘못되면 내용을 노출하지 않고 실패한다")
    void missingAndMalformedCredentialsFailWithoutLeakingContents() throws Exception {
        Path file = directory.resolve("env.json");
        assertThatThrownBy(() -> LocalApplication.prepare(local(file)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("로컬 인증 정보를 먼저 준비해 주세요");
        Files.writeString(file, "{secret-do-not-log");
        assertThatThrownBy(() -> LocalApplication.prepare(local(file)))
                .hasMessageNotContaining("secret-do-not-log")
                .hasNoCause();
    }

    @Test
    @DisplayName("하위 모듈과 컨테이너 디렉토리에서 저장소 루트를 찾는다")
    void rootLookupWorksFromNestedModuleAndContainerDirectory() throws Exception {
        Path backend = directory.resolve("Backend");
        Files.createDirectories(backend.resolve("scripts/local"));
        Files.writeString(backend.resolve("scripts/local/auth-env.cjs"), "");
        assertThat(LocalApplication.root(directory)).isEqualTo(backend);
        assertThat(LocalApplication.root(backend.resolve("services/member-service")))
                .isEqualTo(backend);
    }

    private MockEnvironment local(Path file) {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("local");
        environment.setProperty("shoppinglive.local.credentials", file.toString());
        return environment;
    }
}
