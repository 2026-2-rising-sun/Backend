package com.shoppinglive.live.integration;

import com.shoppinglive.live.integration.commerce.HttpSalesClient;
import com.shoppinglive.live.integration.commerce.SalesClient;
import com.shoppinglive.live.integration.commerce.SalesSnapshot;
import com.shoppinglive.live.integration.commerce.SalesStatus;
import com.shoppinglive.live.integration.shopping.HttpProductClient;
import com.shoppinglive.live.integration.shopping.ProductClient;
import com.shoppinglive.live.integration.shopping.ProductSnapshot;
import java.time.Duration;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 외부 경계마다 실제(http)와 stub 을 둔다. stub 은 local/test 에서만 허용하므로
 * 운영 프로파일의 stub 오설정은 기동 실패로 드러난다. 실제 모드 장애를 stub 성공으로 숨기지 않는다.
 * 주입받은 RestClient.Builder 를 쓰므로 X-Request-Id 전달이 유지된다. 조회 retry 는 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductClientsConfiguration.Properties.class)
public class ProductClientsConfiguration {

    @ConfigurationProperties("live.products")
    public record Properties(String mode, String shoppingUrl, String commerceUrl) { }

    /** stub 은 1=판매중, 2=품절, 3=READY, 4=PRIVATE 이고 그 밖은 미존재다. */
    private static final Set<Long> STUB_PRODUCTS = Set.of(1L, 2L, 3L, 4L);

    @Bean
    ProductClient productClient(final Properties properties, final RestClient.Builder builder,
                                final Environment environment) {
        return switch (mode(properties, environment)) {
            case "http" -> new HttpProductClient(http(builder, properties.shoppingUrl()));
            case "stub" -> ids -> ids.stream().filter(STUB_PRODUCTS::contains)
                .map(id -> new ProductSnapshot(id, "Local demo product " + id, null)).toList();
            default -> ids -> {
                throw new ProductLookupException("shopping",
                    ProductLookupException.Reason.UNAVAILABLE);
            };
        };
    }

    @Bean
    SalesClient salesClient(final Properties properties, final RestClient.Builder builder,
                            final Environment environment) {
        return switch (mode(properties, environment)) {
            case "http" -> new HttpSalesClient(http(builder, properties.commerceUrl()));
            case "stub" -> ids -> ids.stream().filter(STUB_PRODUCTS::contains)
                .map(ProductClientsConfiguration::stubSales).toList();
            default -> ids -> {
                throw new ProductLookupException("commerce",
                    ProductLookupException.Reason.UNAVAILABLE);
            };
        };
    }

    private static SalesSnapshot stubSales(final Long productId) {
        return switch (productId.intValue()) {
            case 1 -> new SalesSnapshot(1L, 101L, 10_000L, SalesStatus.ON_SALE, 5);
            case 2 -> new SalesSnapshot(2L, 102L, 20_000L, SalesStatus.SOLD_OUT, 0);
            case 3 -> new SalesSnapshot(3L, 103L, 30_000L, SalesStatus.READY, 10);
            default -> new SalesSnapshot(4L, 104L, 40_000L, SalesStatus.PRIVATE, 8);
        };
    }

    private static String mode(final Properties properties, final Environment environment) {
        final String mode = properties.mode() == null ? "disabled" : properties.mode();
        if (!Set.of("http", "stub", "disabled").contains(mode)) {
            throw new IllegalArgumentException("Unsupported live.products.mode");
        }
        if (mode.equals("stub") && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalArgumentException("Product stub requires local or test profile");
        }
        return mode;
    }

    static RestClient http(final RestClient.Builder builder, final String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Product upstream URL is required");
        }
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return builder.clone().baseUrl(url).requestFactory(factory).build();
    }

}
