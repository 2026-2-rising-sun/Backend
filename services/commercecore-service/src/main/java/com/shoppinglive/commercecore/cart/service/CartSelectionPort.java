package com.shoppinglive.commercecore.cart.service;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.cart.dto.SelectedCartItem;
import java.util.List;

/** 주문 생성과 성공 결과 처리에서 사용하는 장바구니 경계. */
public interface CartSelectionPort {
    List<SelectedCartItem> readOwned(String memberId, List<CartSelection> selections);

    /** 호출자의 거래 트랜잭션 안에서 항목 ID 순으로 잠그고 선택 버전을 검증한다. */
    List<SelectedCartItem> lockOwned(String memberId, List<CartSelection> selections);

    /** 완료 처리는 중간에 삭제되거나 수정된 행도 허용하고 남아 있는 본인 행만 잠근다. */
    void lockForCompletion(String memberId, List<Long> itemIds);

    /** 이미 잠근 거래 트랜잭션에 참여하며 원본 ID·회원·버전이 같은 행만 정리한다. */
    int deletePurchased(String memberId, List<CartSelection> selections);
}
