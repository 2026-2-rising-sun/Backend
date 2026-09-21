package com.shoppinglive.shopping.product.application;

import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesDisplayStatus;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * 비로그인 공개 상품 목록 (상품 5). 판매중·품절 상품만 최신 등록순으로 보여준다.
 *
 * <p>공개 여부는 Commerce 판매정보에 있어 DB 에서 걸러낼 수 없다. 그래서 상품을 최신순 구간(batch)으로 읽고
 * 구간마다 판매정보를 한 번에 조회해 공개 대상만 남기며, 페이지가 찰 때까지 다음 구간을 이어 읽는다.
 * 앞쪽 상품이 대부분 비공개라는 이유만으로 뒤에 있는 공개 상품을 두고 빈 목록을 돌려주지 않기 위해서다.
 *
 * <p>한 요청이 읽는 구간 수는 {@value #MAX_BATCHES} 개로 제한한다 (비공개 상품이 길게 이어져도 응답 시간과
 * Commerce 호출 수가 묶이도록). 제한에 걸려 멈추면 페이지가 덜 차거나 비어 있어도 {@code hasNext=true} 와
 * 마지막으로 살펴본 위치를 돌려주고, 클라이언트는 그 커서로 이어서 요청한다. 목록의 끝은 {@code hasNext=false}
 * 로만 판단한다 (빈 페이지가 끝을 뜻하지 않는다).
 *
 * <p>커서는 마지막으로 <em>반환한</em> 상품이 아니라 마지막으로 <em>살펴본</em> 상품을 가리킨다. 구간 중간에서
 * 페이지가 차면 거기서 멈추므로, 그 뒤 상품은 다음 요청에서 다시 읽혀 건너뛰는 상품이 없다.
 *
 * <p>판매정보 조회 실패({@link SalesInfoUnavailableException})는 잡지 않는다. 일부 구간만 확인된 페이지나
 * 최신 판매정보 없이 구매 가능해 보이는 상품을 내보내지 않고 503 으로 재시도를 안내한다.
 * 원격 호출 동안 DB 커넥션을 붙잡지 않도록 트랜잭션으로 묶지 않는다 (구간 조회마다 짧은 읽기 트랜잭션).
 */
@Service
public class PublicProductListService {

    static final int MAX_BATCHES = 5;
    private static final int MAX_BATCH_SIZE = 100;

    private final ProductRepository productRepository;
    private final SalesInfoClient salesInfoClient;

    public PublicProductListService(ProductRepository productRepository, SalesInfoClient salesInfoClient) {
        this.productRepository = productRepository;
        this.salesInfoClient = salesInfoClient;
    }

    /**
     * @param after 이전 페이지의 {@link PublicProductPage#next()}. 첫 페이지는 {@code null}
     * @param size  1 이상 (범위 검사는 호출 측)
     * @throws SalesInfoUnavailableException 판매정보를 확인하지 못함
     */
    public PublicProductPage list(ProductListPosition after, int size) {
        int batchSize = Math.min(size * 2, MAX_BATCH_SIZE);
        List<PublicProductPage.Entry> entries = new ArrayList<>(size);
        ProductListPosition position = after;
        boolean sourceExhausted = false;
        boolean batchFullyExamined = true;

        for (int batchCount = 0; batchCount < MAX_BATCHES && entries.size() < size && !sourceExhausted; batchCount++) {
            List<Product> batch = fetch(position, batchSize);
            sourceExhausted = batch.size() < batchSize;
            Map<Long, SalesInfo> salesInfos = salesInfoClient.findByProductIds(batch.stream().map(Product::getId).toList());

            for (int i = 0; i < batch.size() && entries.size() < size; i++) {
                Product product = batch.get(i);
                position = ProductListPosition.of(product);
                batchFullyExamined = i == batch.size() - 1;
                SalesInfo salesInfo = salesInfos.get(product.getId());
                SalesDisplayStatus status = SalesDisplayStatus.from(salesInfo);
                if (status.isPubliclyVisible()) {
                    entries.add(new PublicProductPage.Entry(product, salesInfo, status));
                }
            }
        }

        boolean hasNext = !(sourceExhausted && batchFullyExamined);
        return new PublicProductPage(List.copyOf(entries), hasNext ? position : null, hasNext);
    }

    private List<Product> fetch(ProductListPosition after, int limit) {
        if (after == null) {
            return productRepository.findLatest(Limit.of(limit));
        }
        return productRepository.findLatestBefore(after.createdAt(), after.id(), Limit.of(limit));
    }
}
