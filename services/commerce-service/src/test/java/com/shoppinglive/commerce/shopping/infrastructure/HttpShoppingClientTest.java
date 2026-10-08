package com.shoppinglive.commerce.shopping.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.config.CommerceExceptionHandler;
import com.shoppinglive.commerce.sales.api.SalesController;
import com.shoppinglive.commerce.sales.application.SalesLookupService;
import com.shoppinglive.commerce.sales.application.SalesRegistrationService;
import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.application.ShoppingUnavailableException;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.common.web.GlobalExceptionHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class HttpShoppingClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private HttpShoppingClient client;
    private CircuitBreakerRegistry circuitBreakers;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> callerToken = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int responseStatus = 200;
    private volatile String body = """
        {"success":true,"data":{"id":7,"name":"상품","mainImageUrl":null},"error":null}
        """;
    private volatile boolean delay;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            callerToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            try {
                if (delay) {
                    release.await(5, TimeUnit.SECONDS);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                // HttpServer interprets length=0 as chunked, not as an explicitly empty body.
                exchange.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        circuitBreakers = CircuitBreakerRegistry.ofDefaults();
        client = clientWithReadTimeout(Duration.ofSeconds(3));
    }

    private HttpShoppingClient clientWithReadTimeout(Duration readTimeout) {
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom().maxAttempts(2)
            .waitDuration(Duration.ofMillis(10))
            .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class).build());
        return new ShoppingClientConfiguration().httpShoppingClient(RestClient.builder(),
            new ShoppingClientProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(3), readTimeout, "test-outbound-commerce-shopping-token-32"), circuitBreakers, retries);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void 성공_봉투를_상품스냅샷으로_읽고_이미지는_null을_허용한다() {
        assertThat(client.findProduct(7L)).contains(new ProductSnapshot(7L, "상품", null));
        assertThat(path.get()).isEqualTo("GET /v1/internal/products/7");
        assertThat(callerToken.get()).isEqualTo("test-outbound-commerce-shopping-token-32");
        assertThat(requests).hasValue(1);
    }

    @Test
    void 상품_판매자_식별자를_수신한다() {
        body = """
            {"success":true,"data":{"id":7,"name":"상품","mainImageUrl":null,"sellerId":"seller-a"},"error":null}
            """;
        assertThat(client.findProduct(7L)).contains(new ProductSnapshot(7L, "상품", null, "seller-a"));
    }

    @Test
    void 기존_상품의_null_판매자를_허용한다() {
        body = """
            {"success":true,"data":{"id":7,"name":"상품","mainImageUrl":null,"sellerId":null},"error":null}
            """;
        assertThat(client.findProduct(7L)).contains(new ProductSnapshot(7L, "상품", null));
    }

    @Test
    void 상품404만_미존재이며_재시도하거나_서킷실패로_기록하지_않는다() {
        responseStatus = 404;
        assertThat(client.findProduct(7L)).isEmpty();
        assertThat(requests).hasValue(1);
        assertThat(circuitBreakers.circuitBreaker(HttpShoppingClient.RESILIENCE_INSTANCE)
            .getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void 서버오류는_두번까지만_시도하고_장애로_전파한다(int statusCode) {
        responseStatus = statusCode;
        assertThatThrownBy(() -> client.findProduct(7L)).isInstanceOf(ShoppingUnavailableException.class)
            .hasCauseInstanceOf(HttpServerErrorException.class);
        assertThat(requests).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403})
    void 다른4xx를_상품없음으로_숨기거나_재시도하지_않는다(int statusCode) {
        responseStatus = statusCode;
        assertThatThrownBy(() -> client.findProduct(7L)).isInstanceOf(ShoppingUnavailableException.class)
            .hasCauseInstanceOf(HttpClientErrorException.class);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "null", "{", "{}",
        "{\"success\":false,\"data\":{\"id\":7,\"name\":\"상품\"}}",
        "{\"success\":true,\"data\":null}",
        "{\"success\":true,\"data\":{\"id\":7,\"name\":\"상품\"},\"error\":{\"code\":\"ERROR\"}}",
        "{\"success\":true,\"data\":{\"id\":8,\"name\":\"상품\"}}",
        "{\"success\":true,\"data\":{\"name\":\"상품\"}}",
        "{\"success\":true,\"data\":{\"id\":7}}",
        "{\"success\":true,\"data\":{\"id\":7,\"name\":\" \"}}"
    })
    void 잘못된응답은_재시도없이_장애이며_빈상품이_아니다(String invalidBody) {
        body = invalidBody;
        assertThatThrownBy(() -> client.findProduct(7L)).isInstanceOf(ShoppingUnavailableException.class)
            .hasCauseInstanceOf("{".equals(invalidBody) ? RestClientException.class : IllegalStateException.class)
            .satisfies(error -> assertThat(error.getCause())
                .as("contract failure must not be a network timeout: body=%s", invalidBody)
                .isNotInstanceOf(ResourceAccessException.class));
        assertThat(requests).hasValue(1);
    }

    @Test
    void 실제_read_timeout은_두번후_장애로_종료한다() {
        client = clientWithReadTimeout(Duration.ofMillis(150));
        delay = true;
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.findProduct(7L)).isInstanceOf(ShoppingUnavailableException.class)
            .hasCauseInstanceOf(ResourceAccessException.class).hasRootCauseInstanceOf(SocketTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(requests).hasValue(2);
    }

    @Test
    void 연결거부는_상품없음이_아닌_장애다() {
        server.stop(0);
        assertThatThrownBy(() -> client.findProduct(7L)).isInstanceOf(ShoppingUnavailableException.class)
            .hasCauseInstanceOf(ResourceAccessException.class);
    }

    @Test
    void 열린서킷은_네트워크호출없이_장애로_종료한다() {
        circuitBreakers.circuitBreaker(HttpShoppingClient.RESILIENCE_INSTANCE).transitionToOpenState();
        assertThatThrownBy(() -> client.findProduct(7L)).isInstanceOf(ShoppingUnavailableException.class);
        assertThat(requests).hasValue(0);
    }

    @Test
    void 실제_HTTP_실패는_판매등록503으로_매핑되고_저장트랜잭션을_시작하지_않는다() throws Exception {
        responseStatus = 503;
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        SalesStockJpaRepository stock = mock(SalesStockJpaRepository.class);
        SalesRegistrationService registration = new SalesRegistrationService(
            mock(SalesJpaRepository.class), stock, client, transaction);
        SalesController controller = new SalesController(
            mock(SalesService.class), registration, mock(SalesLookupService.class));
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new CommerceExceptionHandler(), new GlobalExceptionHandler()).build()
            .perform(post("/v1/sales").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":7,\"price\":1000,\"initialStock\":2}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("SHOPPING_UNAVAILABLE"));
        verifyNoInteractions(transaction, stock);
    }
}
