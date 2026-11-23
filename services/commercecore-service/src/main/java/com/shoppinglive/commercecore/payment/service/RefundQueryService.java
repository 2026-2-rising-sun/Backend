package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import com.shoppinglive.commercecore.payment.repository.RefundQueryRepository;
import com.shoppinglive.commercecore.payment.repository.RefundQueryRepository.RequestRow;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundQueryService {
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final RefundQueryRepository repository;

    @Autowired
    public RefundQueryService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            RefundQueryRepository repository) {
        this.groups = groups;
        this.orders = orders;
        this.repository = repository;
    }

    public List<RefundableTarget> refundableTargets(String memberId, String groupNumber) {
        PaymentGroup group =
                groups.findByGroupNumberAndMemberId(groupNumber, memberId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        if (group.isOrderSheet()) return repository.sheetTargets(group);
        // 이전 정책 주문은 Payment 결과를 조회하지 않고 환불 불가 사유만 돌려준다.
        Set<Long> requestedIds = new HashSet<>(repository.requestedOrderIds(group.getId()));
        return orders.findByPaymentGroupIdOrderByIdAsc(group.getId()).stream()
                .map(
                        order ->
                                new RefundableTarget(
                                        order.getId(),
                                        order.getSourceCartItemId(),
                                        order.getProductNameSnapshot(),
                                        order.getQuantity(),
                                        order.getPayableAmount(),
                                        false,
                                        "LEGACY_REFUND_UNAVAILABLE",
                                        order.getUnitPrice(),
                                        order.getStatus() == OrderStatus.REFUNDED
                                                ? order.getQuantity()
                                                : 0,
                                        order.getStatus() != OrderStatus.REFUNDED
                                                        && requestedIds.contains(order.getId())
                                                ? order.getQuantity()
                                                : 0,
                                        order.getStatus() == OrderStatus.REFUNDED
                                                        || requestedIds.contains(order.getId())
                                                ? 0
                                                : order.getQuantity(),
                                        order.getStatus() == OrderStatus.REFUNDED
                                                        || requestedIds.contains(order.getId())
                                                ? 0
                                                : order.getPayableAmount()))
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

    private RefundView refundView(
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
                repository.kafkaRequestId(request.id()) == null ? recoveryView(request) : null,
                repository.kafkaRequestId(request.id()));
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

    private SellerRefundView sellerView(
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
                repository.kafkaRequestId(request.id()) == null ? recoveryView(request) : null);
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
