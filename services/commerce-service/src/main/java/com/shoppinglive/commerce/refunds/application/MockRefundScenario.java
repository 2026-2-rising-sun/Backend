package com.shoppinglive.commerce.refunds.application;

import com.shoppinglive.commerce.refunds.domain.MockRefundOutcome;

public enum MockRefundScenario {
    SUCCESS(MockRefundOutcome.SUCCESS, false, false),
    FAILED(MockRefundOutcome.FAILED, false, false),
    UNKNOWN_BEFORE_RESULT(null, true, false),
    UNKNOWN_AFTER_RESULT(MockRefundOutcome.SUCCESS, false, true);

    private final MockRefundOutcome outcome;
    private final boolean unknownBeforeResult;
    private final boolean loseResponseAfterResult;

    MockRefundScenario(MockRefundOutcome outcome, boolean unknownBeforeResult, boolean loseResponseAfterResult) {
        this.outcome = outcome;
        this.unknownBeforeResult = unknownBeforeResult;
        this.loseResponseAfterResult = loseResponseAfterResult;
    }

    MockRefundOutcome outcome() {
        return outcome;
    }

    boolean unknownBeforeResult() {
        return unknownBeforeResult;
    }

    boolean loseResponseAfterResult() {
        return loseResponseAfterResult;
    }
}
