package com.shoppinglive.commerce.payments.application;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
class PaymentRetryPolicyTest {
    @Test void jitterCapsAndQueryOnlyDelayMatchConfirmedPolicy(){
        var policy=new PaymentRetryPolicy();
        for(int count=0;count<3;count++)for(int i=0;i<100;i++)assertThat(policy.afterUnknown(count)).isBetween(Duration.ZERO,Duration.ofSeconds(1L<<count));
        assertThat(policy.afterUnknown(3)).isEqualTo(Duration.ofMinutes(1));
        assertThatThrownBy(()->policy.afterUnknown(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->policy.afterUnknown(4)).isInstanceOf(IllegalArgumentException.class);
    }
}
