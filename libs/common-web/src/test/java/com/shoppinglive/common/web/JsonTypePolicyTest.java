package com.shoppinglive.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = JsonTypePolicyTest.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class JsonTypePolicyTest {
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired TestRestTemplate http;

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"name\":123}", "{\"name\":1.5}", "{\"name\":true}",
        "{\"quantity\":1.5}", "{\"quantity\":\"2\"}", "{\"quantity\":true}", "{\"quantity\":\"\"}",
        "{\"quantity\":1.00000000000000000001}", "{\"quantity\":9223372036854775808.0}",
        "{\"quantity\":-9223372036854775809.0}", "{\"quantity\":NaN}", "{\"quantity\":Infinity}",
        "{\"price\":\"1.5\"}", "{\"price\":true}", "{\"price\":\"\"}",
        "{\"enabled\":\"true\"}", "{\"enabled\":1}", "{\"enabled\":0.5}", "{\"enabled\":\"\"}",
        "{\"scheduledAt\":123}", "{\"scheduledAt\":1.5}", "{\"scheduledAt\":\"123\"}",
        "{\"scheduledAt\":true}", "{\"scheduledAt\":\"\"}", "{\"scheduledAt\":[]}",
        "{\"scheduledAt\":\"not-a-date\"}"
    })
    void wrongJsonTypesFailInTheBootMapperAndMvc(String body) throws Exception {
        assertThatThrownBy(() -> mapper.readValue(body, Input.class)).isInstanceOf(JsonProcessingException.class);
        mvc.perform(post("/types").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void validNumbersIsoDateTimeAndNullRemainValidOverActualHttp() throws Exception {
        String body = """
            {"name":"상품","quantity":2147483648,"price":1.25,"enabled":true,
             "scheduledAt":"2026-10-01T09:30:00.123456789+09:00"}
            """;
        var response = send(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Input value = mapper.readValue(response.getBody(), Input.class);
        assertThat(value.name()).isEqualTo("상품");
        assertThat(value.quantity()).isEqualTo(2147483648L);
        assertThat(value.price()).isEqualByComparingTo("1.25");
        assertThat(value.enabled()).isTrue();
        assertThat(value.scheduledAt()).isEqualTo(Instant.parse("2026-10-01T00:30:00.123456789Z"));
        var nullable = send("{\"name\":null,\"quantity\":null,\"price\":null,\"enabled\":null,\"scheduledAt\":null}");
        assertThat(nullable.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readValue(nullable.getBody(), Input.class)).isEqualTo(new Input(null, null, null, null, null));
        assertThat(send("{\"quantity\":1,\"price\":2}").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actualHttpRejectsWrongTypesWithoutChangingQueryParameterConversion() throws Exception {
        var response = send("{\"name\":123,\"quantity\":1.5}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(response.getBody()).path("error").path("code").asText()).isEqualTo("INVALID_REQUEST");
        mvc.perform(get("/types").param("quantity", "2")).andExpect(status().isOk()).andExpect(jsonPath("$").value(2));
    }

    @Test
    void treeToValueUsesTheSameStrictTypes() throws Exception {
        var invalid = mapper.readTree("{\"quantity\":1.5}");
        assertThatThrownBy(() -> mapper.treeToValue(invalid, Input.class)).isInstanceOf(MismatchedInputException.class);
        var preciseFraction = mapper.readTree("{\"quantity\":1.00000000000000000001}");
        assertThatThrownBy(() -> mapper.treeToValue(preciseFraction, Input.class)).isInstanceOf(MismatchedInputException.class);
        assertThat(mapper.treeToValue(mapper.readTree("{\"quantity\":2}"), Input.class).quantity()).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1e0", "9223372036854775807.0", "-9223372036854775808.0"})
    void exactIntegerValuesAreAcceptedRegardlessOfJsonNumberNotation(String number) throws Exception {
        String body = "{\"quantity\":" + number + "}";
        long expected = new BigDecimal(number).longValueExact();
        var response = send(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readValue(response.getBody(), Input.class).quantity()).isEqualTo(expected);
        assertThat(mapper.treeToValue(mapper.readTree(body), Input.class).quantity()).isEqualTo(expected);
    }

    @Test
    void integerDeserializerKeepsPrimitiveWrapperAndLargeNumberRangeRules() throws Exception {
        for (Class<?> type : new Class<?>[] {Long.class, long.class, Integer.class, int.class,
                Short.class, short.class, Byte.class, byte.class, BigInteger.class}) {
            for (String valid : new String[] {"1", "1.0", "1e0"}) {
                assertThat(((Number) mapper.readValue(valid, type)).longValue()).isEqualTo(1);
            }
            for (String invalid : new String[] {"1.5", "\"1\"", "true"}) {
                assertThatThrownBy(() -> mapper.readValue(invalid, type)).isInstanceOf(MismatchedInputException.class);
            }
        }
        assertThatThrownBy(() -> mapper.readValue("2147483648.0", Integer.class)).isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("32768.0", Short.class)).isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("128.0", Byte.class)).isInstanceOf(MismatchedInputException.class);
        assertThat(mapper.readValue("9223372036854775808.0", BigInteger.class)).isEqualTo(new BigInteger("9223372036854775808"));
        assertThat(mapper.readValue("null", Integer.class)).isNull();
        assertThat(mapper.readValue("null", int.class)).isZero();
    }

    private org.springframework.http.ResponseEntity<String> send(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/types", new HttpEntity<>(body, headers), String.class);
    }

    record Input(String name, Long quantity, BigDecimal price, Boolean enabled, Instant scheduledAt) { }

    @RestController
    static class Controller {
        @PostMapping("/types") Input body(@RequestBody Input input) { return input; }
        @GetMapping("/types") long query(@RequestParam long quantity) { return quantity; }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Controller.class)
    static class Application { }
}
