package com.shoppinglive.commercecore.payment.relay;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

public class HttpPaymentRelayClient implements PaymentRelayClient {
    private final RestClient preparation;
    private final RestClient approval;
    private final ObjectMapper mapper;

    public HttpPaymentRelayClient(
            RestClient preparation, RestClient approval, ObjectMapper mapper) {
        this.preparation = preparation;
        this.approval = approval;
        this.mapper = mapper;
    }

    public Reply<Created> create(Create request) {
        outsideTransaction();
        return read(preparation.post().uri("/v1/internal/payments").body(request), Created.class);
    }

    public Reply<Checkout> checkout(String orderRef) {
        outsideTransaction();
        return read(
                preparation.post().uri("/v1/internal/payments/{orderRef}/checkouts", orderRef),
                Checkout.class);
    }

    public Reply<Lookup> get(String orderRef) {
        outsideTransaction();
        return read(
                preparation.get().uri("/v1/internal/payments/{orderRef}", orderRef), Lookup.class);
    }

    public Reply<ApprovalResult> approve(String orderRef, Approval request) {
        outsideTransaction();
        return read(
                approval.post()
                        .uri("/v1/internal/payments/{orderRef}/approval", orderRef)
                        .body(request),
                ApprovalResult.class);
    }

    private <T> Reply<T> read(RestClient.RequestHeadersSpec<?> request, Class<T> type) {
        return request.exchange(
                (req, res) -> {
                    var body = mapper.readTree(res.getBody());
                    if (body == null || !body.has("success"))
                        throw new IllegalArgumentException("Payment 응답 봉투가 올바르지 않습니다.");
                    T data =
                            body.path("data").isNull() || body.path("data").isMissingNode()
                                    ? null
                                    : mapper.treeToValue(body.path("data"), type);
                    return new Reply<>(
                            res.getStatusCode().value(),
                            body.path("success").asBoolean(),
                            data,
                            body.path("error").path("code").asText(null),
                            res.getHeaders().getFirst("Location"));
                });
    }

    private void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Core 트랜잭션에서 Payment HTTP를 호출할 수 없습니다.");
    }
}
