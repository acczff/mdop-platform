CREATE TABLE wms_stock_freeze (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 balance_id BIGINT NOT NULL,warehouse_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'FROZEN',reason VARCHAR(500) NOT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,request_hash CHAR(64) NOT NULL,
 frozen_on_hand DECIMAL(18,6) NOT NULL,frozen_available DECIMAL(18,6) NOT NULL,frozen_reserved DECIMAL(18,6) NOT NULL,
 created_by VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 release_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin UNIQUE,release_hash CHAR(64),
 release_reason VARCHAR(500),released_by VARCHAR(64),released_at DATETIME(6),
 released_on_hand DECIMAL(18,6),released_available DECIMAL(18,6),released_reserved DECIMAL(18,6),
 active_balance BIGINT GENERATED ALWAYS AS (IF(status='FROZEN',balance_id,NULL)) STORED UNIQUE,
 FOREIGN KEY(balance_id) REFERENCES wms_inventory_balance(id),FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id),
 CHECK(status IN ('FROZEN','RELEASED')),
 CHECK(frozen_on_hand>0 AND frozen_available>=0 AND frozen_reserved>=0 AND frozen_available+frozen_reserved<=frozen_on_hand),
 CHECK((status='FROZEN' AND release_key IS NULL AND release_hash IS NULL AND release_reason IS NULL AND released_by IS NULL AND released_at IS NULL AND released_on_hand IS NULL AND released_available IS NULL AND released_reserved IS NULL)
 OR (status='RELEASED' AND release_key IS NOT NULL AND release_hash IS NOT NULL AND release_reason IS NOT NULL AND released_by IS NOT NULL AND released_by<>created_by AND released_at IS NOT NULL AND released_on_hand IS NOT NULL AND released_available IS NOT NULL AND released_reserved IS NOT NULL AND released_on_hand>=0 AND released_available>=0 AND released_reserved>=0 AND released_available+released_reserved<=released_on_hand)),
 INDEX ix_freeze_warehouse(warehouse_id,id)
);
ALTER TABLE wms_inventory_balance ADD active_freeze_id BIGINT,
 ADD FOREIGN KEY(active_freeze_id) REFERENCES wms_stock_freeze(id),
 ADD CONSTRAINT ck_frozen_unavailable CHECK(active_freeze_id IS NULL OR available_qty=0);
