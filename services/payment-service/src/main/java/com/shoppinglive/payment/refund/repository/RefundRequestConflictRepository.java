package com.shoppinglive.payment.refund.repository;

import com.shoppinglive.payment.refund.entity.RefundRequestConflict;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRequestConflictRepository
        extends JpaRepository<RefundRequestConflict, Long> {}
