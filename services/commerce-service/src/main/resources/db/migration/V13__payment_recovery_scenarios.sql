ALTER TABLE payment_attempt DROP CONSTRAINT ck_payment_attempt_scenario;
ALTER TABLE payment_attempt ADD CONSTRAINT ck_payment_attempt_scenario CHECK (
    scenario IN (
        'INSTANT_SUCCESS', 'INSTANT_FAIL', 'DELAYED_SUCCESS', 'DELAYED_FAIL',
        'SUCCESS_RESPONSE_LOST', 'FAILURE_RESPONSE_LOST', 'UNAVAILABLE_BEFORE_RESULT'
    )
);
