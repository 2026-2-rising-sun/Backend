package com.shoppinglive.notification.sales.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.notification.sales.dto.RestockAlertRequest;
import com.shoppinglive.notification.sales.service.RestockAlertService;
import com.shoppinglive.notification.subscription.dto.AlertSubscriptionPage;
import com.shoppinglive.notification.subscription.dto.AlertSubscriptionState;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재입고 알림 신청과 해제. 본인 것만 다룬다 — 회원 ID 는 요청 값이 아니라 토큰에서 꺼낸다.
 *
 * <p>둘 다 여러 번 호출해도 결과가 같다. 이미 신청했으면 그대로 201, 신청한 적 없는데 해제하면 그대로 204 다.
 */
@RestController
@RequestMapping("/v1/restock-alerts")
public class RestockAlertController {

    private final RestockAlertService alerts;

    public RestockAlertController(RestockAlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public ApiResponse<AlertSubscriptionPage> list(
            @AuthenticationPrincipal AuthenticatedUser member,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(alerts.list(member.memberId(), cursor, size));
    }

    @GetMapping("/{productId}")
    public ApiResponse<AlertSubscriptionState> state(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable Long productId) {
        return ApiResponse.ok(
                new AlertSubscriptionState(alerts.subscribed(member.memberId(), productId)));
    }

    @PostMapping
    public ResponseEntity<Void> register(
            @AuthenticationPrincipal AuthenticatedUser member,
            @Valid @RequestBody RestockAlertRequest request) {
        alerts.register(member.memberId(), request.productId());
        return ResponseEntity.status(201).build();
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> unregister(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable Long productId) {
        alerts.unregister(member.memberId(), productId);
        return ResponseEntity.noContent().build();
    }
}
