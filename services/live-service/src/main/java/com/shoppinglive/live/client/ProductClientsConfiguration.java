package com.shoppinglive.live.client;

import com.shoppinglive.common.web.ServiceRestClientFactory;
import com.shoppinglive.live.client.commerce.HttpSalesClient;
import com.shoppinglive.live.client.commerce.SalesClient;
import com.shoppinglive.live.client.commerce.SalesSnapshot;
import com.shoppinglive.live.client.commerce.SalesStatus;
import com.shoppinglive.live.client.shopping.HttpProductClient;
import com.shoppinglive.live.client.shopping.ProductClient;
import com.shoppinglive.live.client.shopping.ProductSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.client.RestClient;

/**
 * 외부 경계마다 실제(http)와 stub 을 둔다. stub 은 local/test 에서만 허용하므로 운영 프로파일의 stub 오설정은 기동 실패로 드러난다. 실제 모드
 * 장애를 stub 성공으로 숨기지 않는다. 주입받은 RestClient.Builder 를 쓰므로 X-Request-Id 전달이 유지된다. 조회 retry 는 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductClientsConfiguration.Properties.class)
public class ProductClientsConfiguration {

    @ConfigurationProperties("live.products")
    public record Properties(
            String mode,
            String shoppingUrl,
            String commerceUrl,
            String shoppingToken,
            String commerceToken) {}

    private record StubProduct(long id, long salesId, long price, SalesStatus status, int stock) {}

    /** stub 은 1=판매중, 2=품절, 3=READY, 4=PRIVATE 이고 그 밖은 미존재다. */
    private static final List<StubProduct> STUB_CATALOG =
            List.of(
                    new StubProduct(1L, 101L, 10_000L, SalesStatus.ON_SALE, 5),
                    new StubProduct(2L, 102L, 20_000L, SalesStatus.SOLD_OUT, 0),
                    new StubProduct(3L, 103L, 30_000L, SalesStatus.READY, 10),
                    new StubProduct(4L, 104L, 40_000L, SalesStatus.PRIVATE, 8));

    private static final Set<Long> STUB_PRODUCTS =
            STUB_CATALOG.stream().map(StubProduct::id).collect(Collectors.toUnmodifiableSet());

    @Bean
    ProductClient productClient(
            final Properties properties,
            final RestClient.Builder builder,
            final Environment environment) {
        return switch (mode(properties, environment)) {
            case "http" ->
                    new HttpProductClient(
                            http(builder, properties.shoppingUrl(), properties.shoppingToken()));
            case "stub" ->
                    ids ->
                            ids.stream()
                                    .filter(STUB_PRODUCTS::contains)
                                    .map(id -> new ProductSnapshot(id, "로컬 데모 상품 " + id, null))
                                    .toList();
            default ->
                    ids -> {
                        throw new ProductLookupException(
                                "shopping", ProductLookupException.Reason.UNAVAILABLE);
                    };
        };
    }

    @Bean
    SalesClient salesClient(
            final Properties properties,
            final RestClient.Builder builder,
            final Environment environment) {
        return switch (mode(properties, environment)) {
            case "http" ->
                    new HttpSalesClient(
                            http(builder, properties.commerceUrl(), properties.commerceToken()));
            case "stub" ->
                    ids ->
                            ids.stream()
                                    .filter(STUB_PRODUCTS::contains)
                                    .map(ProductClientsConfiguration::stubSales)
                                    .toList();
            default ->
                    ids -> {
                        throw new ProductLookupException(
                                "commerce", ProductLookupException.Reason.UNAVAILABLE);
                    };
        };
    }

    private static SalesSnapshot stubSales(final Long productId) {
        final StubProduct stub =
                STUB_CATALOG.stream()
                        .filter(product -> product.id() == productId)
                        .findFirst()
                        .orElseThrow();
        return new SalesSnapshot(
                stub.id(), stub.salesId(), stub.price(), stub.status(), stub.stock());
    }

    private static String mode(final Properties properties, final Environment environment) {
        final String mode = properties.mode() == null ? "disabled" : properties.mode();
        if (!Set.of("http", "stub", "disabled").contains(mode)) {
            throw new IllegalArgumentException("지원하지 않는 live.products.mode 값입니다.");
        }
        if (mode.equals("stub") && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalArgumentException("상품 stub은 local 또는 test 프로필에서만 사용할 수 있습니다.");
        }
        return mode;
    }

    static RestClient http(
            final RestClient.Builder builder, final String url, final String serviceToken) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("상품 조회 대상 서비스의 URL이 필요합니다.");
        }
        if (serviceToken == null
                || serviceToken.isBlank()
                || serviceToken.length() < 32
                || !serviceToken.equals(serviceToken.strip())) {
            throw new IllegalArgumentException("상품 조회 대상 서비스의 인증 토큰은 32자 이상이어야 합니다.");
        }
        return ServiceRestClientFactory.create(
                builder, url, serviceToken, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
}
