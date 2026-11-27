-- 승인 거절 뒤 같은 주문에서 다시 승인할 수 있게 시도마다 승인 전달을 남긴다.
-- 진행 중이거나 성공한 승인 전달은 주문당 하나다. 같은 멱등 키는 같은 시도를 가리킨다.
DROP INDEX uk_payment_relay_delivery;
CREATE UNIQUE INDEX uk_payment_relay_delivery ON payment_delivery_request(payment_group_id,kind)
 WHERE kind='PREPARE' OR (kind='APPROVAL' AND status<>'FAILED');
CREATE UNIQUE INDEX uk_payment_relay_approval_key ON payment_delivery_request(payment_group_id,request_key)
 WHERE kind='APPROVAL';
