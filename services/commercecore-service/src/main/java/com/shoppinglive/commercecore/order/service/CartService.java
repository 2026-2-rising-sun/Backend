package com.shoppinglive.commercecore.order.service;

import com.shoppinglive.commercecore.order.entity.CartItem;
import com.shoppinglive.commercecore.order.exception.CartItemNotFoundException;
import com.shoppinglive.commercecore.order.repository.CartItemRepository;
import com.shoppinglive.commercecore.product.exception.ProductNotFoundException;
import com.shoppinglive.commercecore.product.service.ProductLookup;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
    private final CartItemRepository items;
    private final ProductLookup productLookup;

    public CartService(CartItemRepository items, ProductLookup productLookup) {
        this.items = items;
        this.productLookup = productLookup;
    }

    // 상품 존재 확인과 장바구니 저장은 각각의 저장소 호출로 수행한다.
    public CartItem add(String memberId, Long productId, int quantity) {
        if (items.existsByMemberIdAndProductId(memberId, productId)) {
            throw duplicate();
        }
        productLookup
                .findProduct(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        try {
            return items.saveAndFlush(new CartItem(memberId, productId, quantity));
        } catch (DataIntegrityViolationException e) {
            if (items.existsByMemberIdAndProductId(memberId, productId)) {
                throw duplicate();
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<CartItem> list(String memberId) {
        return items.findByMemberIdOrderByIdDesc(memberId);
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
