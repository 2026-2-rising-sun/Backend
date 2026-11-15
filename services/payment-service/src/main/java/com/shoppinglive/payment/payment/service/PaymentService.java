package com.shoppinglive.payment.payment.service;

import com.shoppinglive.payment.config.PaymentError;
import com.shoppinglive.payment.config.PaymentException;
import com.shoppinglive.payment.payment.dto.CreatePaymentRequest;
import com.shoppinglive.payment.payment.dto.SellerAmountRequest;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PaymentStatus;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.payment.repository.PaymentSellerAmountRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 결제 생성·결제창·조회. 토스를 부르지 않고, 시간 판단은 DB {@code now()}로 한다. */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    // 토스 orderId(영문·숫자·-·_, 6–64자)에 "-{결제창 회차}"가 붙어도 64자 안에 들게 50자로 둔다.
    private static final Pattern ORDER_REF = Pattern.compile("^[A-Za-z0-9_-]{6,50}$");

    private final PaymentRepository payments;
    private final PaymentSellerAmountRepository sellerAmounts;
    private final JdbcTemplate jdbc;

    public PaymentService(
            PaymentRepository payments,
            PaymentSellerAmountRepository sellerAmounts,
            JdbcTemplate jdbc) {
        this.payments = payments;
        this.sellerAmounts = sellerAmounts;
        this.jdbc = jdbc;
    }

    public record Created(Payment payment, boolean isNew) {}

    @Transactional
    public Created create(CreatePaymentRequest request) {
        String orderRef = request.orderRef();
        if (!ORDER_REF.matcher(orderRef).matches()) {
            throw new PaymentException(PaymentError.INVALID_ORDER_REF);
        }
        long sum = request.sellerAmounts().stream().mapToLong(SellerAmountRequest::amount).sum();
        if (sum != request.amount()) {
            throw new PaymentException(PaymentError.SUM_MISMATCH);
        }
        long distinct =
                request.sellerAmounts().stream()
                        .map(SellerAmountRequest::sellerOrderNumber)
                        .distinct()
                        .count();
        if (distinct != request.sellerAmounts().size()) {
            throw new PaymentException(PaymentError.INVALID_REQUEST, "판매자 주문 번호가 요청 안에서 겹칩니다.");
        }
        Instant now = dbNow();
        Instant expiresAt = request.expiresAt().toInstant();
        if (!expiresAt.isAfter(now)) {
            throw new PaymentException(PaymentError.INVALID_EXPIRES_AT);
        }

        String buyerId = request.buyerId().toString();
        int inserted =
                payments.insertIfAbsent(
                        orderRef, buyerId, request.amount(), expiresAt, orderRef + "-1");
        // ON CONFLICT 대상을 생략해 다른 UNIQUE 충돌도 0행이 된다. 그때는 order_ref 행이 없으니 원인을 남긴다.
        Payment payment =
                payments.findByOrderRef(orderRef)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "결제 INSERT가 0행인데 orderRef 행이 없다: " + orderRef));
        if (inserted == 0) {
            requireReusable(payment, request.amount(), buyerId, now);
            return new Created(payment, false);
        }
        // 같은 번호를 잘못 재사용한 두 요청이 반대 순서로 넣어 교착되지 않게 번호 순으로 넣는다.
        List<SellerAmountRequest> sellers =
                request.sellerAmounts().stream()
                        .sorted(Comparator.comparing(SellerAmountRequest::sellerOrderNumber))
                        .toList();
        for (SellerAmountRequest seller : sellers) {
            int saved =
                    sellerAmounts.insertIfAbsent(
                            payment.getId(),
                            seller.sellerOrderNumber(),
                            seller.sellerId().toString(),
                            seller.amount(),
                            seller.commissionRate());
            if (saved == 0) {
                // 정상 Commerce라면 생기지 않는다. 예외로 결제 행까지 되돌린다.
                log.warn(
                        "판매자 주문 번호가 다른 결제에 이미 있다: orderRef={}, sellerOrderNumber={}",
                        orderRef,
                        seller.sellerOrderNumber());
                throw new PaymentException(
                        PaymentError.INVALID_REQUEST, "판매자 주문 번호가 이미 다른 결제에 있습니다.");
            }
        }
        return new Created(payment, true);
    }

    @Transactional
    public Payment openCheckout(String orderRef) {
        if (payments.openCheckout(orderRef) == 1) {
            return payments.findByOrderRef(orderRef).orElseThrow();
        }
        Payment payment =
                payments.findByOrderRef(orderRef)
                        .orElseThrow(() -> new PaymentException(PaymentError.NOT_READY));
        if (payment.getStatus() == PaymentStatus.APPROVING
                || payment.getStatus() == PaymentStatus.UNKNOWN) {
            throw new PaymentException(PaymentError.PAYMENT_CLOSED, "결제를 처리하고 있습니다.");
        }
        throw new PaymentException(PaymentError.PAYMENT_CLOSED);
    }

    @Transactional(readOnly = true)
    public Payment get(String orderRef) {
        return payments.findByOrderRef(orderRef)
                .orElseThrow(() -> new PaymentException(PaymentError.PAYMENT_NOT_FOUND));
    }

    /** 같은 orderRef 재요청: 금액·구매자만 비교하고 expiresAt·판매자 금액은 처음 값을 유지한다. */
    private static void requireReusable(Payment payment, long amount, String buyerId, Instant now) {
        if (payment.getAmount() != amount || !payment.getBuyerId().equals(buyerId)) {
            throw new PaymentException(PaymentError.AMOUNT_CONFLICT);
        }
        boolean reusable =
                switch (payment.getStatus()) {
                    case CREATED -> payment.getExpiresAt().isAfter(now);
                    case APPROVING, UNKNOWN, APPROVED -> true;
                    case CANCELED, PARTIALLY_REFUNDED, REFUNDED -> false;
                };
        if (!reusable) {
            throw new PaymentException(PaymentError.PAYMENT_CLOSED);
        }
    }

    private Instant dbNow() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }
}
