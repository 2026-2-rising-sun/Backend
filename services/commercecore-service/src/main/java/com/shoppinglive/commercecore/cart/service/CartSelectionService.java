package com.shoppinglive.commercecore.cart.service;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.cart.dto.SelectedCartItem;
import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.exception.CartItemNotFoundException;
import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartSelectionService implements CartSelectionPort {
    private static final int MAX_SELECTIONS = 100;
    private final CartItemRepository items;
    @PersistenceContext private EntityManager entityManager;

    public CartSelectionService(CartItemRepository items) {
        this.items = items;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SelectedCartItem> readOwned(String memberId, List<CartSelection> selections) {
        String owner = owner(memberId);
        return validated(selections).stream()
                .map(
                        selection ->
                                snapshot(
                                        items.findByIdAndMemberId(selection.itemId(), owner)
                                                .orElseThrow(CartItemNotFoundException::new),
                                        selection))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SelectedCartItem> lockOwned(String memberId, List<CartSelection> selections) {
        String owner = owner(memberId);
        return validated(selections).stream()
                .map(
                        selection -> {
                            CartItem item =
                                    items.lockOwned(selection.itemId(), owner)
                                            .orElseThrow(CartItemNotFoundException::new);
                            // 먼저 읽은 관리 객체가 있어도 잠근 DB 행의 최신 수량·버전으로 판정한다.
                            entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
                            return snapshot(item, selection);
                        })
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForCompletion(String memberId, List<Long> itemIds) {
        String owner = owner(memberId);
        if (itemIds == null || itemIds.isEmpty() || itemIds.size() > MAX_SELECTIONS) {
            throw invalidSelection();
        }
        if (itemIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(itemIds).size() != itemIds.size()) {
            throw invalidSelection();
        }
        itemIds.stream().sorted().forEach(id -> items.lockOwned(id, owner));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deletePurchased(String memberId, List<CartSelection> selections) {
        String owner = owner(memberId);
        int deleted = 0;
        for (CartSelection selection : validated(selections)) {
            deleted += items.deletePurchased(selection.itemId(), owner, selection.version());
        }
        return deleted;
    }

    private SelectedCartItem snapshot(CartItem item, CartSelection selection) {
        if (!Objects.equals(item.getVersion(), selection.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "장바구니 항목이 변경되었습니다. 다시 확인해 주세요.");
        }
        if (item.getSkuId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "판매 단위가 준비되지 않은 장바구니 항목입니다.");
        }
        return new SelectedCartItem(
                item.getId(),
                item.getSkuId(),
                item.getProductId(),
                item.getQuantity(),
                item.getVersion());
    }

    private List<CartSelection> validated(List<CartSelection> selections) {
        if (selections == null || selections.isEmpty() || selections.size() > MAX_SELECTIONS) {
            throw invalidSelection();
        }
        var ids = new HashSet<Long>();
        for (CartSelection selection : selections) {
            if (selection == null
                    || selection.itemId() == null
                    || selection.itemId() <= 0
                    || selection.version() == null
                    || selection.version() < 0
                    || !ids.add(selection.itemId())) {
                throw invalidSelection();
            }
        }
        return selections.stream().sorted(Comparator.comparing(CartSelection::itemId)).toList();
    }

    private String owner(String memberId) {
        try {
            return UUID.fromString(memberId).toString();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "회원 식별자가 올바르지 않습니다.");
        }
    }

    private BusinessException invalidSelection() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "장바구니 선택 항목과 버전을 확인해 주세요.");
    }
}
