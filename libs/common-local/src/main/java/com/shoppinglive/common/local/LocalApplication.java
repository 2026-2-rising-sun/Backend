package com.shoppinglive.common.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/** 기본 Application 실행에서만 로컬 설정을 준비한다. 명시적 배포 프로필은 그대로 유지한다. */
public final class LocalApplication {
    private static final Set<String> KEYS = Set.of(
        "MEMBER_JWT_PUBLIC_KEY_SET_LOCATION", "MEMBER_JWT_PRIVATE_KEY_LOCATION", "MEMBER_JWT_KEY_ID",
        "MEMBER_ACCESS_TOKEN_TTL", "MEMBER_REFRESH_TOKEN_TTL", "MEMBER_SESSION_BASE_URL",
        "SHOPPING_MEMBER_SERVICE_TOKEN", "COMMERCE_MEMBER_SERVICE_TOKEN", "LIVE_MEMBER_SERVICE_TOKEN",
        "COMMERCE_SHOPPING_SERVICE_TOKEN", "LIVE_SHOPPING_SERVICE_TOKEN",
        "SHOPPING_COMMERCE_SERVICE_TOKEN", "LIVE_COMMERCE_SERVICE_TOKEN");

    private LocalApplication() {}

    public static ConfigurableApplicationContext run(Class<?> applicationClass, String... args) {
        SpringApplication application = new SpringApplication(applicationClass);
        application.setDefaultProperties(Map.of("spring.profiles.default", "local"));
        application.addInitializers(context -> prepare(context.getEnvironment()));
        return application.run(args);
    }

    static void prepare(ConfigurableEnvironment environment) {
        // 명시적 local을 포함한 기존 실행 스크립트의 자격 증명 주입 방식도 유지한다.
        if (environment.getActiveProfiles().length != 0
            || !environment.acceptsProfiles(Profiles.of("local & !dev & !prod & !production"))) return;
        String configured = environment.getProperty("shoppinglive.local.credentials");
        Path file = configured == null ? root(Path.of(System.getProperty("user.dir")))
            .resolve("build/local/ide-auth/env.json") : Path.of(configured);
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            JsonNode record = new ObjectMapper().readTree(Files.readString(file));
            if (record == null || !"isolated local development only".equals(record.path("purpose").asText()))
                throw new IllegalStateException("Expected generated local credentials");
            for (String key : KEYS) {
                JsonNode value = record.path("env").path(key);
                if (!value.isTextual() || value.asText().isBlank())
                    throw new IllegalStateException("Missing local configuration: " + key);
                values.put(key, value.asText());
            }
        } catch (IOException e) {
            // JSON 오류에는 비밀값이 포함될 수 있으므로 원문과 cause를 로그에 노출하지 않는다.
            throw new IllegalStateException("Prepare local credentials first: node scripts/local/auth-env.cjs create "
                + "--access-ttl PT15M --refresh-ttl P30D --out build/local/ide-auth");
        }
        // CLI·시스템 속성·환경변수를 우선하고 생성 파일은 로컬 기본값으로만 쓴다.
        environment.getPropertySources().addLast(new MapPropertySource("shoppingliveLocalCredentials", values));
    }

    static Path root(Path start) {
        for (Path path = start.toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("scripts/local/auth-env.cjs"))) return path;
            Path backend = path.resolve("Backend");
            if (Files.isRegularFile(backend.resolve("scripts/local/auth-env.cjs"))) return backend;
        }
        throw new IllegalStateException("Run the Application from the Backend checkout, or set shoppinglive.local.credentials");
    }
}
