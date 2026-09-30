-- Run only through purge-development-guest-orders.sh, with Commerce fully stopped.
BEGIN;
LOCK TABLE orders, payment_attempt, sales_stock, sales_info IN ACCESS EXCLUSIVE MODE;
CREATE TEMP TABLE guest_reservations ON COMMIT DROP AS
SELECT sales_info_id, SUM(quantity)::bigint AS quantity
FROM orders WHERE member_id IS NULL AND status IN ('PENDING_PAYMENT', 'PAYMENT_CONFIRMING')
GROUP BY sales_info_id;
-- Abort instead of guessing if existing reservations are inconsistent.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM guest_reservations g
        LEFT JOIN sales_stock s ON s.sales_info_id = g.sales_info_id
        WHERE s.sales_info_id IS NULL OR s.reserved < g.quantity
            OR s.available::bigint + g.quantity > 2147483647) THEN
        RAISE EXCEPTION 'Guest reservation mismatch; preserve database and investigate';
    END IF;
END $$;
UPDATE sales_stock s SET available = s.available + g.quantity,
    reserved = s.reserved - g.quantity, updated_at = CURRENT_TIMESTAMP
FROM guest_reservations g WHERE s.sales_info_id = g.sales_info_id;
UPDATE sales_info s SET status = 'ON_SALE', version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE status = 'SOLD_OUT' AND EXISTS (
    SELECT 1 FROM guest_reservations g JOIN sales_stock st ON st.sales_info_id = g.sales_info_id
    WHERE g.sales_info_id = s.id AND st.available > 0);
DELETE FROM payment_attempt WHERE order_id IN (SELECT id FROM orders WHERE member_id IS NULL);
DELETE FROM orders WHERE member_id IS NULL;
COMMIT;
