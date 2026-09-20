package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 도메인 유스케이스 서비스.
 *
 * <p>가격 변경(판매 2)·재고 조정(판매 3)·상태 전이(판매 4) 등을 담당한다. 각 유스케이스는
 * 자체 트랜잭션을 갖고, 동시성은 DB 계층에서 낙관적 락(가격) 또는 조건부 UPDATE
 * (재고·상태) 로 보장한다.
 */
@Service
public class SalesService {

    private final SalesJpaRepository salesRepository;
    private final SalesStockJpaRepository salesStockRepository;

    public SalesService(
        SalesJpaRepository salesRepository, SalesStockJpaRepository salesStockRepository) {
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
        Sales sales = salesRepository.findById(salesId)
            .orElseThrow(() -> new SalesNotFoundException("sales not found: id=" + salesId));
        sales.changePrice(newPrice);
        return sales;
    }

    /**
     * 재고 available 을 delta 만큼 증감한다 (판매 3, 관리자 조정 대상).
     *
     * <p>조건부 UPDATE 로 안전을 보장한다. {@code reserved} 는 여기서 손대지 않는다.
     *
     * @throws SalesNotFoundException 재고 row 가 없을 때 (404)
     * @throws InsufficientStockException 감소 후 available 이 음수가 되는 경우 (409)
     */
    @Transactional
    public SalesStock adjustAvailable(Long salesId, int delta) {
        int updated = salesStockRepository.adjustAvailable(salesId, delta);
        if (updated == 0) {
            if (!salesStockRepository.existsById(salesId)) {
                throw new SalesNotFoundException("sales stock not found: id=" + salesId);
            }
            throw new InsufficientStockException(
                "cannot adjust stock: id=" + salesId + ", delta=" + delta);
        }
        return salesStockRepository.findById(salesId).orElseThrow(
            () -> new SalesNotFoundException(
                "sales stock disappeared after update: id=" + salesId));
    }

    /**
     * 재고를 조회한다.
     *
     * @throws SalesNotFoundException 재고 row 가 없을 때
     */
    @Transactional(readOnly = true)
    public SalesStock getStock(Long salesId) {
        return salesStockRepository.findById(salesId)
            .orElseThrow(() -> new SalesNotFoundException(
                "sales stock not found: id=" + salesId));
    }

    /**
     * 판매 상태를 변경한다 (판매 4).
     *
     * <p>흐름:
     * <ol>
     *   <li>대상 상태가 관리자 지정 가능한 값인지 검증 (ON_SALE · PRIVATE 만 허용)</li>
     *   <li>현재 상태 로드</li>
     *   <li>PRIVATE → ON_SALE 요청이고 재고 available == 0 이면 실제 전이 대상을 SOLD_OUT 으로 조정</li>
     *   <li>도메인 전이 규칙 검증 ({@link SalesStatus#canTransitionTo})</li>
     *   <li>조건부 UPDATE 실행. 실패(row 0)면 다른 트랜잭션이 먼저 바꿨다는 뜻</li>
     * </ol>
     *
     * @param salesId 판매정보 식별자
     * @param requestedTarget 관리자가 요청한 상태 (ON_SALE 또는 PRIVATE)
     * @return 전이 후 판매정보
     * @throws SalesNotFoundException 판매정보가 없을 때 (404)
     * @throws IllegalStateTransitionException 대상이 관리자 지정 불가이거나 전이 규칙 위반 (400)
     * @throws ConcurrentStateChangeException 다른 트랜잭션이 먼저 전이했을 때 (409)
     */
    @Transactional
    public Sales changeStatus(Long salesId, SalesStatus requestedTarget) {
        if (requestedTarget == null || !requestedTarget.isAdminChangeable()) {
            throw new IllegalStateTransitionException(
                "target status is not admin-changeable: " + requestedTarget);
        }

        Sales sales = salesRepository.findById(salesId)
            .orElseThrow(() -> new SalesNotFoundException("sales not found: id=" + salesId));
        SalesStatus current = sales.getStatus();

        SalesStatus effectiveTarget = requestedTarget;
        // PRIVATE 에서 재개 요청인데 재고가 없으면 SOLD_OUT 으로 자동 조정
        if (current == SalesStatus.PRIVATE && requestedTarget == SalesStatus.ON_SALE) {
            Optional<SalesStock> stock = salesStockRepository.findById(salesId);
            if (stock.isEmpty() || stock.get().getAvailable() == 0) {
                effectiveTarget = SalesStatus.SOLD_OUT;
            }
        }

        if (!current.canTransitionTo(effectiveTarget)) {
            throw new IllegalStateTransitionException(current, requestedTarget);
        }

        int updated = salesRepository.transitionStatus(
            salesId, current.name(), effectiveTarget.name());
        if (updated == 0) {
            throw new ConcurrentStateChangeException(salesId);
        }

        return salesRepository.findById(salesId).orElseThrow(
            () -> new SalesNotFoundException(
                "sales disappeared after status update: id=" + salesId));
    }
}
