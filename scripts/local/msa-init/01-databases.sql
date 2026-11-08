-- 이 Compose의 새 전용 볼륨에서만 실행하며 기존 개발 DB에는 연결하지 않는다.
CREATE DATABASE catalog;
CREATE DATABASE commercecore;
CREATE DATABASE payment;
CREATE DATABASE member;
CREATE DATABASE notification;
CREATE DATABASE live;
CREATE DATABASE catalog_test;
CREATE DATABASE commercecore_test;
CREATE DATABASE payment_test;
CREATE DATABASE member_test;
CREATE DATABASE notification_test;
CREATE DATABASE live_test;
-- 병렬 worker의 스키마 초기화가 다른 worker의 테스트와 겹치지 않게 분리한다.
CREATE DATABASE catalog_product_test;
CREATE DATABASE commercecore_product_test;
CREATE DATABASE commercecore_payment_test;
CREATE DATABASE payment_execution_test;
