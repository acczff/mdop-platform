CREATE TABLE wms_purchase_return (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    receipt_item_id BIGINT NOT NULL,
    quantity DECIMAL(18,6) NOT NULL,
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
    confirm_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
    confirm_hash CHAR(64) NULL,
    handover_no VARCHAR(64) NULL,
    confirmed_by VARCHAR(64) NULL,
    confirmed_at DATETIME(6) NULL,
    cancel_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
    cancel_hash CHAR(64) NULL,
    FOREIGN KEY(receipt_id) REFERENCES wms_receipt(id),
    FOREIGN KEY(receipt_item_id) REFERENCES wms_quality_item(receipt_item_id),
    CHECK(quantity>0),
    CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED','RETURNED')),
    INDEX ix_return_item(receipt_item_id,status),
    INDEX ix_return_receipt(receipt_id,id)
);
CREATE TABLE wms_purchase_return_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    FOREIGN KEY(return_id) REFERENCES wms_purchase_return(id)
);
ALTER TABLE wms_inventory_transaction
    ADD purchase_return_id BIGINT NULL,
    ADD CONSTRAINT fk_ledger_return FOREIGN KEY(purchase_return_id) REFERENCES wms_purchase_return(id),
    ADD UNIQUE KEY uk_ledger_return(purchase_return_id),
    ADD single_operation_type VARCHAR(16) GENERATED ALWAYS AS (CASE WHEN transaction_type<>'RETURN_OUT' THEN transaction_type ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_ledger_single(receipt_item_id,single_operation_type);
ALTER TABLE wms_inventory_transaction DROP INDEX uk_ledger_item_type;
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK (
    before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
    ((transaction_type IN ('RECEIPT','QUALITY_PASS','QUALITY_REJECT','PUTAWAY_IN') AND change_qty>0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL) OR
     (transaction_type IN ('QUALITY_OUT','PUTAWAY_OUT') AND change_qty<0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL) OR
     (transaction_type='REVERSAL' AND change_qty<0 AND reversed_transaction_id IS NOT NULL AND purchase_return_id IS NULL) OR
     (transaction_type='RETURN_OUT' AND change_qty<0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NOT NULL))
);
ALTER TABLE wms_outbox ADD business_key VARCHAR(64) NOT NULL DEFAULT '',
    ADD UNIQUE KEY uk_outbox_business(aggregate_id,event_type,business_key);
ALTER TABLE wms_outbox DROP INDEX uk_outbox_receipt_event;
