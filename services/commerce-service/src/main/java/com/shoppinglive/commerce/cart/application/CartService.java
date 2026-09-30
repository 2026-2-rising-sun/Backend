package com.shoppinglive.commerce.cart.application;

import com.shoppinglive.commerce.cart.domain.CartItem;
import com.shoppinglive.commerce.cart.infrastructure.CartItemRepository;
import com.shoppinglive.commerce.shopping.application.ProductNotFoundException;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
    private final CartItemRepository items;
    private final ShoppingClient shopping;

    public CartService(CartItemRepository items, ShoppingClient shopping) {
        this.items = items;
        this.shopping = shopping;
    }

    // No outer transaction: an unavailable Shopping service must not hold a DB transaction open.
    public CartItem add(String memberId, Long productId, int quantity) {
        if (items.existsByMemberIdAndProductId(memberId, productId)) throw duplicate();
        shopping.findProduct(productId).orElseThrow(() -> new ProductNotFoundException(productId));
        try {
            return items.saveAndFlush(new CartItem(memberId, productId, quantity));
        } catch (DataIntegrityViolationException e) {
            if (items.existsByMemberIdAndProductId(memberId, productId)) throw duplicate();
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<CartItem> list(String memberId) { return items.findByMemberIdOrderByIdDesc(memberId); }

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
