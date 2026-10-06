ALTER TABLE wms_receipt
    ADD correction_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD downstream_stage VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD CONSTRAINT ck_receipt_correction CHECK (correction_status IN ('NONE','REVERSED')),
    ADD CONSTRAINT ck_receipt_downstream CHECK (downstream_stage IN ('NONE','INSPECTION','PUTAWAY'));

ALTER TABLE wms_inventory_transaction
    ADD transaction_type VARCHAR(16) NOT NULL DEFAULT 'RECEIPT',
    ADD reversed_transaction_id BIGINT NULL,
    ADD UNIQUE KEY uk_ledger_item_type(receipt_item_id,transaction_type),
    ADD UNIQUE KEY uk_ledger_reversed(reversed_transaction_id),
    ADD CONSTRAINT fk_ledger_original FOREIGN KEY(reversed_transaction_id) REFERENCES wms_inventory_transaction(id);
ALTER TABLE wms_inventory_transaction DROP INDEX receipt_item_id;
ALTER TABLE wms_inventory_transaction DROP CHECK wms_inventory_transaction_chk_1;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK (
    before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
    ((transaction_type='RECEIPT' AND change_qty>0 AND reversed_transaction_id IS NULL) OR
     (transaction_type='REVERSAL' AND change_qty<0 AND reversed_transaction_id IS NOT NULL))
);

CREATE TABLE wms_receiving_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    arrival_id BIGINT NOT NULL,
    receipt_id BIGINT NULL,
    arrival_item_id BIGINT NULL,
    difference_type VARCHAR(24) NULL,
    observed_material VARCHAR(64) NULL,
    quantity DECIMAL(18,6) NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    version BIGINT NOT NULL DEFAULT 0,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    decision_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
    decision_hash CHAR(64) NULL,
    decided_by VARCHAR(64) NULL,
    decided_at DATETIME(6) NULL,
    decision_reason VARCHAR(500) NULL,
    active_receipt_id BIGINT GENERATED ALWAYS AS (CASE WHEN kind='REVERSAL' AND status IN ('PENDING','APPROVED') THEN receipt_id ELSE NULL END) STORED,
    UNIQUE KEY uk_case_active_reversal(active_receipt_id),
    FOREIGN KEY(arrival_id) REFERENCES wms_arrival_notice(id),
    FOREIGN KEY(receipt_id) REFERENCES wms_receipt(id),
    FOREIGN KEY(arrival_item_id) REFERENCES wms_arrival_notice_item(id),
    CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CHECK ((kind='REVERSAL' AND receipt_id IS NOT NULL AND difference_type IS NULL AND quantity IS NULL) OR
           (kind='DIFFERENCE' AND receipt_id IS NULL AND difference_type IN ('SHORT','OVER','DAMAGED','WRONG_MATERIAL') AND quantity>0)),
    INDEX ix_case_arrival(arrival_id,id)
);
CREATE TABLE wms_receiving_case_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    FOREIGN KEY(case_id) REFERENCES wms_receiving_case(id)
);
