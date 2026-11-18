package com.shoppinglive.payment.payment.repository;

import com.shoppinglive.payment.payment.entity.PaymentSellerAmount;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentSellerAmountRepository extends JpaRepository<PaymentSellerAmount, Long> {
    List<PaymentSellerAmount> findByPaymentId(Long paymentId);
}
