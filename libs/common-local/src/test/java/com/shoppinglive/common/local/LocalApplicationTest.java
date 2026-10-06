package com.shoppinglive.common.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class LocalApplicationTest {
    @TempDir Path directory;

    @Test
    void explicitDeploymentAndTestProfilesNeverReadLocalCredentials() {
        for (String profile : new String[]{"dev", "prod", "test", "local", "production"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            environment.setProperty("shoppinglive.local.credentials", "/does/not/exist");
            assertThatCode(() -> LocalApplication.prepare(environment)).doesNotThrowAnyException();
            assertThat(environment.getPropertySources().contains("shoppingliveLocalCredentials")).isFalse();
        }
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("local", "dev");
        assertThatCode(() -> LocalApplication.prepare(mixed)).doesNotThrowAnyException();
    }

    @Test
    void localCredentialsAreSharedFallbacksWithoutOverwritingExplicitValues() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : new String[]{"MEMBER_JWT_PUBLIC_KEY_SET_LOCATION", "MEMBER_JWT_PRIVATE_KEY_LOCATION",
            "MEMBER_JWT_KEY_ID", "MEMBER_ACCESS_TOKEN_TTL", "MEMBER_REFRESH_TOKEN_TTL",
            "MEMBER_SESSION_BASE_URL", "SHOPPING_MEMBER_SERVICE_TOKEN", "COMMERCE_MEMBER_SERVICE_TOKEN",
            "LIVE_MEMBER_SERVICE_TOKEN", "COMMERCE_SHOPPING_SERVICE_TOKEN", "LIVE_SHOPPING_SERVICE_TOKEN",
            "SHOPPING_COMMERCE_SERVICE_TOKEN", "LIVE_COMMERCE_SERVICE_TOKEN"}) values.put(key, "generated");
        Path file = directory.resolve("env.json");
        Files.writeString(file, new ObjectMapper().writeValueAsString(
            Map.of("purpose", "isolated local development only", "env", values)));
        MockEnvironment environment = local(file);
        environment.setProperty("LIVE_MEMBER_SERVICE_TOKEN", "explicit");

        LocalApplication.prepare(environment);

        assertThat(environment.getProperty("MEMBER_JWT_KEY_ID")).isEqualTo("generated");
        assertThat(environment.getProperty("LIVE_MEMBER_SERVICE_TOKEN")).isEqualTo("explicit");
    }

    @Test
    void missingAndMalformedCredentialsFailWithoutLeakingContents() throws Exception {
        Path file = directory.resolve("env.json");
        assertThatThrownBy(() -> LocalApplication.prepare(local(file)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Prepare local credentials first");
        Files.writeString(file, "{secret-do-not-log");
        assertThatThrownBy(() -> LocalApplication.prepare(local(file)))
            .hasMessageNotContaining("secret-do-not-log").hasNoCause();
    }

    @Test
    void rootLookupWorksFromNestedModuleAndContainerDirectory() throws Exception {
        Path backend = directory.resolve("Backend");
        Files.createDirectories(backend.resolve("scripts/local"));
        Files.writeString(backend.resolve("scripts/local/auth-env.cjs"), "");
        assertThat(LocalApplication.root(directory)).isEqualTo(backend);
        assertThat(LocalApplication.root(backend.resolve("services/member-service"))).isEqualTo(backend);
    }

    private MockEnvironment local(Path file) {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("local");
        environment.setProperty("shoppinglive.local.credentials", file.toString());
        return environment;
    }
}
