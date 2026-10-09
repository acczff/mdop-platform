ALTER TABLE wms_arrival_notice
    ADD COLUMN purchase_arrangement_id BIGINT NULL,
    ADD COLUMN purchase_payload_hash CHAR(64) NULL,
    ADD COLUMN withdrawn_by VARCHAR(100) NULL,
    ADD COLUMN withdrawn_reason VARCHAR(500) NULL,
    ADD COLUMN withdrawn_at DATETIME(6) NULL,
    ADD UNIQUE KEY uk_wms_purchase_arrangement (purchase_arrangement_id);
ALTER TABLE wms_arrival_notice DROP CHECK wms_arrival_notice_chk_1;
ALTER TABLE wms_arrival_notice ADD CONSTRAINT ck_arrival_status CHECK (status IN ('PENDING_RECEIPT','PARTIALLY_RECEIVED','RECEIVED','WITHDRAWN'));
ALTER TABLE wms_arrival_notice_item
    ADD COLUMN purchase_arrangement_line_id BIGINT NULL,
    ADD COLUMN purchase_order_line_id BIGINT NULL,
    ADD UNIQUE KEY uk_wms_purchase_arrangement_line (purchase_arrangement_line_id);
