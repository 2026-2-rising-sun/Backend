package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 도메인 유스케이스 서비스.
 *
 * <p>가격 변경·재고 조정·상태 전이 등 판매정보 관련 명령을 담당한다. 이번 이슈(#15)에서는
 * 가격 변경만 구현하고, 재고(판매 3)와 상태 전이(판매 4)는 후속 이슈에서 추가한다.
 */
@Service
public class SalesService {

    private final SalesJpaRepository salesRepository;

    public SalesService(SalesJpaRepository salesRepository) {
        this.salesRepository = salesRepository;
    }

    /**
     * 판매정보의 가격을 변경한다.
     *
     * <p>JPA {@code @Version} 낙관적 락이 동시 변경으로 인한 lost update 를 방어한다. 트랜잭션
     * 커밋 시점의 dirty checking 이 실제 UPDATE 를 수행한다.
     *
     * @param salesId 판매정보 식별자
     * @param newPrice 새 가격 (원 단위 양의 정수)
     * @return 변경 반영된 판매정보 (관리 상태의 엔티티)
     * @throws SalesNotFoundException 판매정보가 존재하지 않을 때
     * @throws IllegalArgumentException 가격이 {@code null} 이거나 0 이하일 때
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException
     *         다른 트랜잭션이 먼저 갱신했을 때 (재조회 후 재시도 필요)
     */
    @Transactional
    public Sales changePrice(Long salesId, Long newPrice) {
        Sales sales = salesRepository.findById(salesId)
            .orElseThrow(() -> new SalesNotFoundException("sales not found: id=" + salesId));
        sales.changePrice(newPrice);
        return sales;
    }
}
