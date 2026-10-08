package com.shoppinglive.commerce.purchase.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="mock_gateway_result")
public class MockGatewayResult {
 @Id @Column(name="attempt_id") private Long attemptId;
 @Column(nullable=false,length=32) private String outcome;
 @Column(name="authorized_at",nullable=false) private Instant authorizedAt;
 protected MockGatewayResult() {}
}
