package com.shoppinglive.common.security.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** 로컬 전용 실행 도구. 생성 파일은 기본적으로 무시되는 build/local-auth 아래에 놓인다. */
public final class LocalAuthFixtures {
    private LocalAuthFixtures() { }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        JwtTestTokens tokens = new JwtTestTokens();
        Path privateKey = directory.resolve("member-private.pem");
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(tokens.privateKeyPkcs8())
            + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(privateKey, pem);
        if (Files.getFileStore(privateKey).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(privateKey, PosixFilePermissions.fromString("rw-------"));
        }
        Files.writeString(directory.resolve("member-public.jwks"), tokens.publicJwkSet().toString());
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        new ObjectMapper().writeValue(directory.resolve("tokens.json").toFile(), Map.of(
            "memberA", tokens.token(JwtTestTokens.MEMBER_A, Set.of("USER")),
            "memberB", tokens.token(JwtTestTokens.MEMBER_B, Set.of("USER")),
            "seller", tokens.token(JwtTestTokens.SELLER, Set.of("USER", "SELLER")),
            "sampleServiceToken", Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
            "keyId", tokens.keyId(), "warning", "LOCAL TEST ONLY; member tokens expire in five minutes"));
        System.out.println("Generated local-only authentication fixtures in " + directory.toAbsolutePath());
    }
}
