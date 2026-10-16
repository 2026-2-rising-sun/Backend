package com.shoppinglive.commerce.sales.service;

import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.event.SalesInfoEventTracker;
import com.shoppinglive.commerce.sales.exception.ConcurrentStateChangeException;
import com.shoppinglive.commerce.sales.exception.IllegalStateTransitionException;
import com.shoppinglive.commerce.sales.exception.InsufficientStockException;
import com.shoppinglive.commerce.sales.exception.SalesNotFoundException;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 도메인 유스케이스 서비스.
 *
 * <p>가격 변경(판매 2)·재고 조정(판매 3)·상태 전이(판매 4) 등을 담당한다. 각 유스케이스는 자체 트랜잭션을 갖고, 동시성은 DB 계층에서 낙관적 락(가격) 또는
 * 조건부 UPDATE (재고·상태) 로 보장한다.
 */
@Service
public class SalesService {

    private final SalesInfoEventTracker events;
    private final SalesJpaRepository salesRepository;
    private final SalesStockJpaRepository salesStockRepository;

    public SalesService(
            SalesJpaRepository salesRepository,
            SalesStockJpaRepository salesStockRepository,
            SalesInfoEventTracker events) {
        this.events = events;
        this.salesRepository = salesRepository;
        this.salesStockRepository = salesStockRepository;
    }

    /**
     * 판매정보의 가격을 변경한다. `@Version` 낙관적 락으로 lost update 방어.
     *
     * @throws SalesNotFoundException 판매정보가 없을 때
     * @throws IllegalArgumentException 가격이 유효하지 않을 때
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException 동시 변경 감지
     */
    @Transactional
    public Sales changePrice(Long salesId, Long newPrice) {
        events.watch(salesId);
        Sales sales =
                salesRepository
                        .findById(salesId)
                        .orElseThrow(
                                () -> new SalesNotFoundException("판매정보를 찾을 수 없습니다: id=" + salesId));
        sales.changePrice(newPrice);
        return sales;
    }

    /**
     * 재고 available 을 delta 만큼 증감한다 (판매 3, 판매자 조정 대상).
     *
     * <p>조건부 UPDATE 로 안전을 보장한다. {@code reserved} 는 여기서 손대지 않는다.
     *
     * @throws SalesNotFoundException 재고 row 가 없을 때 (404)
     * @throws InsufficientStockException 감소 후 available 이 음수가 되는 경우 (409)
     */
    @Transactional
    public SalesStock adjustAvailable(Long salesId, int delta) {
        events.watch(salesId);
        int updated = salesStockRepository.adjustAvailable(salesId, delta);
        if (updated == 0) {
            if (!salesStockRepository.existsById(salesId)) {
                throw new SalesNotFoundException("판매 재고를 찾을 수 없습니다: id=" + salesId);
            }
            throw new InsufficientStockException(
                    "재고를 조정할 수 없습니다: id=" + salesId + ", delta=" + delta);
        }
        salesRepository.reopenIfStockAvailable(salesId);
        return salesStockRepository
                .findById(salesId)
                .orElseThrow(
                        () -> new SalesNotFoundException("변경 후 판매 재고를 찾을 수 없습니다: id=" + salesId));
    }

    /** 취소·만료·결제 실패의 재고 복구와 품절 해제를 같은 트랜잭션으로 처리한다. */
    @Transactional
    public void restoreReserved(Long salesId, int quantity) {
        events.watch(salesId);
        if (salesStockRepository.restoreReserved(salesId, quantity) != 1) {
            throw new IllegalStateException("복원할 예약 재고가 부족합니다: salesId=" + salesId);
        }
        // 판매자가 숨긴 PRIVATE와 준비 중 READY는 자동 공개하지 않는다.
        salesRepository.reopenIfStockAvailable(salesId);
    }

    /**
     * 재고를 조회한다.
     *
     * @throws SalesNotFoundException 재고 row 가 없을 때
     */
    @Transactional(readOnly = true)
    public SalesStock getStock(Long salesId) {
        return salesStockRepository
                .findById(salesId)
                .orElseThrow(() -> new SalesNotFoundException("판매 재고를 찾을 수 없습니다: id=" + salesId));
    }

    /**
     * 판매 상태를 변경한다 (판매 4).
     *
     * <p>흐름:
     *
     * <ol>
     *   <li>대상 상태가 판매자 지정 가능한 값인지 검증 (ON_SALE · PRIVATE 만 허용)
     *   <li>현재 상태 로드
     *   <li>ON_SALE 요청은 재고 확인. 재고 0이면 PRIVATE는 SOLD_OUT, SOLD_OUT은 유지하고 나머지는 거절
     *   <li>도메인 전이 규칙 검증 ({@link SalesStatus#canTransitionTo})
     *   <li>조건부 UPDATE 실행. 실패(row 0)면 다른 트랜잭션이 먼저 바꿨다는 뜻
     * </ol>
     *
     * @param salesId 판매정보 식별자
     * @param requestedTarget 판매자가 요청한 상태 (ON_SALE 또는 PRIVATE)
     * @return 전이 후 판매정보
     * @throws SalesNotFoundException 판매정보가 없을 때 (404)
     * @throws IllegalStateTransitionException 대상이 판매자 지정 불가이거나 전이 규칙 위반 (400)
     * @throws ConcurrentStateChangeException 다른 트랜잭션이 먼저 전이했을 때 (409)
     */
    @Transactional
    public Sales changeStatus(Long salesId, SalesStatus requestedTarget) {
        if (requestedTarget == null || !requestedTarget.isSellerChangeable()) {
            throw new IllegalStateTransitionException(
                    "판매자가 직접 변경할 수 없는 판매 상태입니다: " + requestedTarget);
        }

        events.watch(salesId);
        Sales sales =
                salesRepository
                        .findById(salesId)
                        .orElseThrow(
                                () -> new SalesNotFoundException("판매정보를 찾을 수 없습니다: id=" + salesId));
        SalesStatus current = sales.getStatus();

        SalesStatus effectiveTarget = requestedTarget;
        // PRIVATE 재공개는 기존 계약대로 재고가 없으면 SOLD_OUT으로 표시한다.
        if (requestedTarget == SalesStatus.ON_SALE) {
            Optional<SalesStock> stock = salesStockRepository.findById(salesId);
            if (stock.isEmpty() || stock.get().getAvailable() == 0) {
                if (current == SalesStatus.PRIVATE) {
                    effectiveTarget = SalesStatus.SOLD_OUT;
                } else if (current == SalesStatus.SOLD_OUT) {
                    return sales;
                } else {
                    throw new InsufficientStockException("판매를 시작할 재고가 없습니다: id=" + salesId);
                }
            }
        }

        if (!current.canTransitionTo(effectiveTarget)) {
            throw new IllegalStateTransitionException(current, requestedTarget);
        }

        int updated =
                salesRepository.transitionStatus(salesId, current.name(), effectiveTarget.name());
        if (updated == 0) {
            throw new ConcurrentStateChangeException(salesId);
        }

        return salesRepository
                .findById(salesId)
                .orElseThrow(
                        () -> new SalesNotFoundException("상태 변경 후 판매정보를 찾을 수 없습니다: id=" + salesId));
    }
}
