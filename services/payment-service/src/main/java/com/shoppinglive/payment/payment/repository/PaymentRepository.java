package com.shoppinglive.payment.payment.repository;

import com.shoppinglive.payment.payment.entity.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderRef(String orderRef);
}
