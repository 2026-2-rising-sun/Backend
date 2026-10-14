ALTER TABLE sales_info ADD COLUMN snapshot_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE sales_info ADD CONSTRAINT ck_sales_snapshot_version_positive CHECK (snapshot_version > 0);
