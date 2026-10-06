ALTER TABLE wms_inventory_balance DROP CHECK wms_inventory_balance_chk_1;
ALTER TABLE wms_inventory_balance ADD CONSTRAINT ck_balance_quality CHECK (
    quality_status IN ('PENDING_INSPECTION','QUALIFIED','REJECTED') AND on_hand_qty>=0
    AND available_qty>=0 AND available_qty<=on_hand_qty
    AND (quality_status='QUALIFIED' OR available_qty=0)
);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK (
    before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
    ((transaction_type IN ('RECEIPT','QUALITY_PASS','QUALITY_REJECT','PUTAWAY_IN') AND change_qty>0 AND reversed_transaction_id IS NULL) OR
     (transaction_type IN ('QUALITY_OUT','PUTAWAY_OUT') AND change_qty<0 AND reversed_transaction_id IS NULL) OR
     (transaction_type='REVERSAL' AND change_qty<0 AND reversed_transaction_id IS NOT NULL))
);
CREATE TABLE wms_quality_result (
    receipt_id BIGINT PRIMARY KEY,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    reference_no VARCHAR(64) NOT NULL UNIQUE,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY(receipt_id) REFERENCES wms_receipt(id)
);
CREATE TABLE wms_quality_item (
    receipt_item_id BIGINT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    qualified_qty DECIMAL(18,6) NOT NULL,
    rejected_qty DECIMAL(18,6) NOT NULL,
    putaway_location_id BIGINT NULL,
    putaway_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
    putaway_hash CHAR(64) NULL,
    putaway_by VARCHAR(64) NULL,
    putaway_at DATETIME(6) NULL,
    FOREIGN KEY(receipt_item_id) REFERENCES wms_receipt_item(id),
    FOREIGN KEY(receipt_id) REFERENCES wms_quality_result(receipt_id),
    FOREIGN KEY(putaway_location_id) REFERENCES mdm_location(id),
    CHECK (qualified_qty>=0 AND rejected_qty>=0 AND qualified_qty+rejected_qty>0)
);
