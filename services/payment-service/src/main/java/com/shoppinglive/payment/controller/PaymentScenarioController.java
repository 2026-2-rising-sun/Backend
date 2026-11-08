package com.shoppinglive.payment.controller;

import com.shoppinglive.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.payment.entity.PaymentScenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/internal/payment-scenarios")
@Profile("(local | test) & !dev & !prod")
@ConditionalOnProperty(
        prefix = "payment.dev.payment-scenario",
        name = "enabled",
        havingValue = "true")
public class PaymentScenarioController {
    private final DevPaymentScenarioRegistry scenarios;

    public PaymentScenarioController(DevPaymentScenarioRegistry scenarios) {
        this.scenarios = scenarios;
    }

    public record ScenarioRequest(@NotNull PaymentScenario scenario) {}

    @PutMapping("/{referenceNumber}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void set(
            @PathVariable String referenceNumber, @Valid @RequestBody ScenarioRequest request) {
        scenarios.set(referenceNumber, request.scenario());
    }

    @DeleteMapping("/{referenceNumber}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String referenceNumber) {
        scenarios.clear(referenceNumber);
    }
}
