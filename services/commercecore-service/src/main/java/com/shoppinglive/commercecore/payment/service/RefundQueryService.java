package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import com.shoppinglive.commercecore.payment.repository.RefundQueryRepository;
import com.shoppinglive.commercecore.payment.repository.RefundQueryRepository.RequestRow;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundQueryService {
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PaymentClient attempts;
    private final RefundQueryRepository repository;
    private final Clock clock;

    @Autowired
    public RefundQueryService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PaymentClient attempts,
            RefundQueryRepository repository) {
        this(groups, orders, attempts, repository, Clock.systemUTC());
    }

    RefundQueryService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PaymentClient attempts,
            RefundQueryRepository repository,
            Clock clock) {
        this.groups = groups;
        this.orders = orders;
        this.attempts = attempts;
        this.repository = repository;
        this.clock = clock;
    }

    public List<RefundableTarget> refundableTargets(String memberId, String groupNumber) {
        PaymentGroup group =
                groups.findByGroupNumberAndMemberId(groupNumber, memberId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        List<Order> groupOrders = orders.findByPaymentGroupIdOrderByIdAsc(group.getId());
        PaymentExecution attempt =
                group.getPaymentId() == null ? null : attempts.get(group.getPaymentId());
        String groupReason = null;
        if (group.getStatus() != OrderStatus.PAID
                || groupOrders.stream()
                        .anyMatch(
                                order ->
                                        order.getStatus() != OrderStatus.PAID
                                                && order.getStatus() != OrderStatus.REFUNDED)) {
            groupReason = "PAYMENT_NOT_COMPLETED";
        } else if (attempt == null
                || attempt.getStatus() != PaymentStatus.SUCCESS
                || attempt.getResolvedAt() == null) {
            groupReason = "PAYMENT_NOT_COMPLETED";
        } else if (!RefundWindowPolicy.isOpen(clock.instant(), attempt.getResolvedAt())) {
            groupReason = "REFUND_WINDOW_EXPIRED";
        }
        Set<Long> requestedIds = new HashSet<>(repository.requestedOrderIds(group.getId()));
        Map<Long, Long> cartCounts =
                groupOrders.stream()
                        .filter(order -> order.getSourceCartItemId() != null)
                        .collect(
                                Collectors.groupingBy(
                                        Order::getSourceCartItemId, Collectors.counting()));
        String reasonForGroup = groupReason;
        return groupOrders.stream()
                .map(
                        order -> {
                            String reason = reasonForGroup;
                            if (reason == null
                                    && (order.getStatus() == OrderStatus.REFUNDED
                                            || requestedIds.contains(order.getId())))
                                reason = "ALREADY_REQUESTED";
                            if (reason == null && order.getSourceCartItemId() == null)
                                reason = "CART_ITEM_UNAVAILABLE";
                            if (reason == null && cartCounts.get(order.getSourceCartItemId()) != 1L)
                                reason = "CART_ITEM_AMBIGUOUS";
                            return new RefundableTarget(
                                    order.getId(),
                                    order.getSourceCartItemId(),
                                    order.getProductNameSnapshot(),
                                    order.getQuantity(),
                                    order.getPayableAmount(),
                                    reason == null,
                                    reason);
                        })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RefundView> list(String memberId, String groupNumber) {
        PaymentGroup group =
                groups.findByGroupNumberAndMemberId(groupNumber, memberId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        List<RequestRow> requests = repository.requests(group.getId());
        if (requests.isEmpty()) return List.of();
        Map<Long, List<RefundTarget>> targets = repository.targetsForGroup(group.getId());
        Map<Long, Long> cumulative = repository.cumulativeAmounts(List.of(group.getId()));
        return requests.stream()
                .map(
                        request ->
                                refundView(
                                        request,
                                        groupNumber,
                                        targets.getOrDefault(request.id(), List.of()),
                                        cumulative.getOrDefault(request.groupId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RefundView get(String memberId, String groupNumber, long requestId) {
        PaymentGroup group =
                groups.findByGroupNumberAndMemberId(groupNumber, memberId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        Long matched = repository.ownedRequestId(requestId, group.getId(), memberId);
        if (matched == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        }
        return load(requestId, groupNumber);
    }

    @Transactional(readOnly = true)
    public List<SellerRefundView> listSeller(String sellerId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("page는 0 이상, size는 1~100이어야 합니다.");
        }
        List<RequestRow> requests = repository.sellerRequests(sellerId, size, (long) page * size);
        if (requests.isEmpty()) return List.of();
        List<Long> ids = requests.stream().map(RequestRow::id).toList();
        List<Long> groupIds = requests.stream().map(RequestRow::groupId).distinct().toList();
        Map<Long, List<RefundTarget>> targets = repository.sellerTargetsByRequestIds(ids, sellerId);
        Map<Long, Long> cumulative = repository.sellerCumulativeAmounts(groupIds, sellerId);
        return requests.stream()
                .map(
                        request ->
                                sellerView(
                                        request,
                                        targets.getOrDefault(request.id(), List.of()),
                                        cumulative.getOrDefault(request.groupId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SellerRefundView getSeller(String sellerId, long requestId) {
        Integer count = repository.sellerTargetCount(requestId, sellerId);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        }
        return loadSeller(requestId, sellerId);
    }

    RefundView load(long id, String groupNumber) {
        RequestRow request = repository.request(id);
        if (request == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        }
        long cumulative = repository.cumulativeAmount(request.groupId());
        List<RefundTarget> targets = repository.targets(id);
        return refundView(request, groupNumber, targets, cumulative);
    }

    private static RefundView refundView(
            RequestRow request, String groupNumber, List<RefundTarget> targets, long cumulative) {
        return new RefundView(
                request.id(),
                groupNumber,
                request.amount(),
                cumulative,
                request.status(),
                request.requestedAt(),
                request.resolvedAt(),
                targets,
                recoveryView(request));
    }

    private SellerRefundView loadSeller(long id, String sellerId) {
        RequestRow request = repository.request(id);
        if (request == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        }
        List<RefundTarget> targets = repository.sellerTargets(id, sellerId);
        Long cumulative = repository.sellerCumulativeAmount(request.groupId(), sellerId);
        return sellerView(request, targets, cumulative == null ? 0L : cumulative);
    }

    private static SellerRefundView sellerView(
            RequestRow request, List<RefundTarget> targets, long cumulative) {
        long sellerAmount = 0;
        try {
            for (RefundTarget target : targets) {
                sellerAmount = Math.addExact(sellerAmount, target.refundAmount());
            }
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.CONFLICT, "환불 금액 범위를 초과했습니다.");
        }
        return new SellerRefundView(
                request.id(),
                sellerAmount,
                cumulative,
                request.status(),
                request.requestedAt(),
                request.resolvedAt(),
                targets,
                recoveryView(request));
    }

    private static RefundRecoveryView recoveryView(RequestRow request) {
        if (request.status() == RefundStatus.SUCCESS || request.status() == RefundStatus.FAILED) {
            return null;
        }
        return new RefundRecoveryView(
                request.retryCount(), request.retryCount() == 3, request.nextActionAt());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
