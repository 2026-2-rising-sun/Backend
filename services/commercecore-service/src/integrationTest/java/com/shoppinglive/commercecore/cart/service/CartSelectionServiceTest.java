package com.shoppinglive.commercecore.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.cart.dto.SelectedCartItem;
import com.shoppinglive.commercecore.order.entity.CartItem;
import com.shoppinglive.commercecore.order.exception.CartItemNotFoundException;
import com.shoppinglive.commercecore.order.repository.CartItemRepository;
import com.shoppinglive.common.core.BusinessException;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
        properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = CartSelectionServiceTest.JpaConfiguration.class)
class CartSelectionServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.shoppinglive.commercecore")
    @EnableJpaRepositories(basePackageClasses = CartItemRepository.class)
    @Import({
        CartSelectionService.class,
        com.shoppinglive.common.persistence.JpaAuditingConfig.class
    })
    static class JpaConfiguration {}

    private static final String MEMBER_A = "11111111-1111-4111-8111-111111111111";
    private static final String MEMBER_B = "22222222-2222-4222-8222-222222222222";
    @Autowired CartSelectionPort selections;
    @Autowired CartItemRepository items;
    @Autowired EntityManager entityManager;

    @Test
    @DisplayName("선택 항목은 요청 순서와 무관하게 ID 오름차순으로 잠그고 반환한다")
    void locksSelectionsInIdOrder() {
        CartItem first = add(1L, 2);
        CartItem second = add(2L, 5);

        var selected = selections.lockOwned(MEMBER_A, List.of(selection(second), selection(first)));

        assertThat(selected)
                .extracting(SelectedCartItem::itemId)
                .containsExactly(first.getId(), second.getId());
        assertThat(selected).extracting(SelectedCartItem::quantity).containsExactly(2, 5);
    }

    @Test
    @DisplayName("다른 회원의 선택 항목은 읽거나 잠글 수 없다")
    void rejectsSelectionsOwnedByAnotherMember() {
        CartItem item = add(1L, 2);
        var input = List.of(selection(item));

        assertThatThrownBy(() -> selections.readOwned(MEMBER_B, input))
                .isInstanceOf(CartItemNotFoundException.class);
        assertThatThrownBy(() -> selections.lockOwned(MEMBER_B, input))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    @DisplayName("수량 변경 전 버전으로 주문을 선택하면 충돌로 거절한다")
    void rejectsStaleSelectionVersion() {
        CartItem item = add(1L, 2);
        CartSelection old = selection(item);
        item.changeQuantity(3);
        items.saveAndFlush(item);
        entityManager.clear();

        assertThatThrownBy(() -> selections.lockOwned(MEMBER_A, List.of(old)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("변경");
    }

    @Test
    @DisplayName("비어 있거나 중복되거나 잘못된 버전의 선택 목록을 거절한다")
    void rejectsInvalidSelections() {
        assertThatThrownBy(() -> selections.readOwned(MEMBER_A, List.of()))
                .isInstanceOf(BusinessException.class);
        var duplicated = List.of(new CartSelection(1L, 0L), new CartSelection(1L, 0L));
        assertThatThrownBy(() -> selections.lockOwned(MEMBER_A, duplicated))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () -> selections.readOwned(MEMBER_A, List.of(new CartSelection(1L, -1L))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("성공 정리는 원본 버전만 삭제하고 변경 항목과 선택하지 않은 항목을 유지한다")
    void deletesOnlyUnchangedPurchasedSelections() {
        CartItem first = add(1L, 2);
        CartItem changed = add(2L, 5);
        CartItem unselected = add(3L, 8);
        var purchased = List.of(selection(first), selection(changed));
        changed.changeQuantity(6);
        items.saveAndFlush(changed);
        entityManager.clear();

        selections.lockForCompletion(MEMBER_A, List.of(changed.getId(), first.getId()));
        assertThat(selections.deletePurchased(MEMBER_A, purchased)).isEqualTo(1);
        entityManager.clear();
        assertThat(items.findById(first.getId())).isEmpty();
        assertThat(items.findById(changed.getId()).orElseThrow().getQuantity()).isEqualTo(6);
        assertThat(items.findById(unselected.getId()).orElseThrow().getQuantity()).isEqualTo(8);

        CartItem readded = add(1L, 4);
        selections.lockForCompletion(MEMBER_A, List.of(first.getId(), changed.getId()));
        assertThat(selections.deletePurchased(MEMBER_A, purchased)).isZero();
        entityManager.clear();
        assertThat(items.findById(readded.getId()).orElseThrow().getQuantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("다른 회원으로 성공 정리를 호출해도 항목을 삭제하지 않는다")
    void neverDeletesAnotherMembersItem() {
        CartItem item = add(1L, 2);

        selections.lockForCompletion(MEMBER_B, List.of(item.getId()));
        assertThat(selections.deletePurchased(MEMBER_B, List.of(selection(item)))).isZero();
        assertThat(items.existsById(item.getId())).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("잠금과 성공 정리는 호출자의 트랜잭션 없이 독립 실행하지 않는다")
    void requiresCallersTransaction() {
        var input = List.of(new CartSelection(1L, 0L));
        assertThatThrownBy(() -> selections.lockOwned(MEMBER_A, input))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> selections.deletePurchased(MEMBER_A, input))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private CartItem add(Long productId, int quantity) {
        return items.saveAndFlush(new CartItem(MEMBER_A, productId, quantity));
    }

    private CartSelection selection(CartItem item) {
        return new CartSelection(item.getId(), item.getVersion());
    }
}
