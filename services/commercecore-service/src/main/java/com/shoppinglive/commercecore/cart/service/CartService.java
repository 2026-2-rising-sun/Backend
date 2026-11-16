package com.shoppinglive.commercecore.cart.service;

import com.shoppinglive.commercecore.cart.dto.CartProductGroup;
import com.shoppinglive.commercecore.cart.dto.CartSkuView;
import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.exception.CartItemNotFoundException;
import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.exception.ProductNotFoundException;
import com.shoppinglive.commercecore.product.service.ProductLookup;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
    private final CartItemRepository items;
    private final ProductLookup productLookup;
    private final SkuService skus;

    public CartService(CartItemRepository items, ProductLookup productLookup, SkuService skus) {
        this.items = items;
        this.productLookup = productLookup;
        this.skus = skus;
    }

    /** 기존 상품 ID 요청은 SKU가 정확히 하나일 때만 호환한다. */
    public CartItem add(String memberId, Long productId, int quantity) {
        productLookup
                .findProduct(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        List<SkuView> variants = skus.findByProductId(productId);
        if (variants.size() != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "담을 SKU를 지정해 주세요.");
        }
        return addSku(memberId, variants.getFirst().skuId(), quantity);
    }

    /** 담기는 조회와 저장만 수행하며 재고 예약이나 거래 잠금을 요청하지 않는다. */
    public CartItem addSku(String memberId, Long skuId, int quantity) {
        SkuView sku =
                skus.findBySkuIds(List.of(skuId)).stream()
                        .filter(value -> value.skuId().equals(skuId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "SKU를 찾을 수 없습니다."));
        productLookup
                .findProduct(sku.productId())
                .orElseThrow(() -> new ProductNotFoundException(sku.productId()));
        if (items.existsByMemberIdAndSkuId(memberId, skuId)) {
            throw duplicate();
        }
        try {
            return items.saveAndFlush(new CartItem(memberId, sku.productId(), skuId, quantity));
        } catch (DataIntegrityViolationException e) {
            if (items.existsByMemberIdAndSkuId(memberId, skuId)) {
                throw duplicate();
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<CartItem> list(String memberId) {
        return items.findByMemberIdOrderByIdDesc(memberId);
    }

    @Transactional(readOnly = true)
    public List<CartProductGroup> listGrouped(String memberId) {
        List<CartItem> stored = items.findByMemberIdOrderByIdDesc(memberId);
        if (stored.isEmpty()) {
            return List.of();
        }
        List<Long> skuIds =
                stored.stream()
                        .map(CartItem::getSkuId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, SkuView> skuById =
                skuIds.isEmpty()
                        ? Map.of()
                        : skus.findBySkuIds(skuIds).stream()
                                .collect(Collectors.toMap(SkuView::skuId, Function.identity()));
        Map<Long, ProductSnapshot> productById =
                productLookup
                        .findProducts(
                                stored.stream().map(CartItem::getProductId).distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));
        Map<Long, List<CartItem>> grouped = new LinkedHashMap<>();
        for (CartItem item : stored) {
            grouped.computeIfAbsent(item.getProductId(), ignored -> new ArrayList<>()).add(item);
        }
        return grouped.entrySet().stream()
                .map(
                        group -> {
                            ProductSnapshot product = productById.get(group.getKey());
                            boolean saleEnabled = product != null && product.saleEnabled();
                            return new CartProductGroup(
                                    group.getKey(),
                                    product == null ? null : product.name(),
                                    product == null ? null : product.mainImageUrl(),
                                    saleEnabled,
                                    group.getValue().stream()
                                            .map(
                                                    item ->
                                                            view(
                                                                    item,
                                                                    item.getSkuId() == null
                                                                            ? null
                                                                            : skuById.get(
                                                                                    item
                                                                                            .getSkuId()),
                                                                    saleEnabled))
                                            .toList());
                        })
                .toList();
    }

    private CartSkuView view(CartItem item, SkuView candidate, boolean saleEnabled) {
        SkuView sku =
                candidate != null && Objects.equals(candidate.productId(), item.getProductId())
                        ? candidate
                        : null;
        boolean purchasable =
                saleEnabled
                        && sku != null
                        && sku.status() == SalesStatus.ON_SALE
                        && sku.available() >= item.getQuantity();
        return new CartSkuView(
                item.getId(),
                item.getSkuId(),
                item.getQuantity(),
                item.getVersion(),
                sku == null ? null : sku.price(),
                sku == null ? null : sku.available(),
                sku == null ? null : sku.status().name(),
                purchasable);
    }

    @Transactional
    public CartItem changeQuantity(String memberId, Long id, int quantity) {
        CartItem item = items.lockOwned(id, memberId).orElseThrow(CartItemNotFoundException::new);
        item.changeQuantity(quantity);
        return items.saveAndFlush(item);
    }

    @Transactional
    public void delete(String memberId, Long id) {
        items.delete(items.lockOwned(id, memberId).orElseThrow(CartItemNotFoundException::new));
    }

    private BusinessException duplicate() {
        return new BusinessException(ErrorCode.CONFLICT, "이미 장바구니에 있는 상품입니다. 수량을 변경해 주세요.");
    }
}
