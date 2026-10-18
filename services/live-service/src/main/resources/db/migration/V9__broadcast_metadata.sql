ALTER TABLE broadcast ADD COLUMN host_name varchar(80) NOT NULL DEFAULT '';
ALTER TABLE broadcast ADD COLUMN description varchar(2000) NOT NULL DEFAULT '';
ALTER TABLE broadcast ADD COLUMN thumbnail_id varchar(36);
CREATE TABLE broadcast_thumbnail (
    id varchar(36) PRIMARY KEY,
    seller_id varchar(36) NOT NULL,
    content_type varchar(32) NOT NULL,
    content bytea NOT NULL
);
ALTER TABLE broadcast ADD CONSTRAINT fk_broadcast_thumbnail FOREIGN KEY (thumbnail_id) REFERENCES broadcast_thumbnail(id);
