package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 도메인 유스케이스 서비스.
 *
 * <p>가격 변경(판매 2)·재고 조정(판매 3)·상태 전이(판매 4) 등을 담당한다. 각 유스케이스는
 * 자체 트랜잭션을 갖고, 동시성은 DB 계층에서 낙관적 락(가격·상태) 또는 조건부 UPDATE
 * (재고) 로 보장한다.
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
     * @param salesId 판매정보 식별자
     * @param delta 증감량 (양수: 추가, 음수: 감소)
     * @throws SalesNotFoundException 재고 row 가 없을 때 (404)
     * @throws InsufficientStockException 감소 후 available 이 음수가 되는 경우 (409)
     */
    @Transactional
    public SalesStock adjustAvailable(Long salesId, int delta) {
        int updated = salesStockRepository.adjustAvailable(salesId, delta);
        if (updated == 0) {
            // updated=0 은 두 원인이 가능: (a) row 없음, (b) 재고 부족.
            // 원인별로 다른 응답 코드 (404 vs 409) 를 주기 위해 존재 확인.
            if (!salesStockRepository.existsById(salesId)) {
                throw new SalesNotFoundException(
                    "sales stock not found: id=" + salesId);
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
}
