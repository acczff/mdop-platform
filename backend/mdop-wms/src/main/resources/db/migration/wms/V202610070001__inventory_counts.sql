CREATE TABLE wms_stock_count (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 warehouse_id BIGINT NOT NULL,
 balance_id BIGINT NOT NULL,
 snapshot_qty DECIMAL(18,6) NOT NULL,
 snapshot_available DECIMAL(18,6) NOT NULL,
 last_ledger_id BIGINT NOT NULL,
 counted_qty DECIMAL(18,6) NULL,
 reason VARCHAR(500) NULL,
 decision_reason VARCHAR(500) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 created_by VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 reviewed_by VARCHAR(64) NULL,
 reviewed_at DATETIME(6) NULL,
 FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id),
 FOREIGN KEY(balance_id) REFERENCES wms_inventory_balance(id),
 CHECK(snapshot_qty>=0 AND snapshot_available>=0 AND snapshot_available<=snapshot_qty),
 CHECK(counted_qty IS NULL OR counted_qty>=0),
 CHECK(status IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELLED')),
 CHECK(status NOT IN ('PENDING','APPROVED','REJECTED') OR (counted_qty IS NOT NULL AND reason IS NOT NULL)),
 CHECK(status NOT IN ('APPROVED','REJECTED') OR (reviewed_by IS NOT NULL AND reviewed_by<>created_by)),
 INDEX ix_count_warehouse(warehouse_id,id)
);
ALTER TABLE wms_inventory_transaction ADD count_id BIGINT NULL,
 ADD CONSTRAINT fk_ledger_count FOREIGN KEY(count_id) REFERENCES wms_stock_count(id),
 ADD UNIQUE KEY uk_ledger_count(count_id);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK (
    before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
    ((count_id IS NOT NULL AND transfer_id IS NULL AND receipt_id IS NULL AND receipt_item_id IS NULL AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL AND ((transaction_type='COUNT_GAIN' AND change_qty>0) OR (transaction_type='COUNT_LOSS' AND change_qty<0))) OR (count_id IS NULL AND ((transfer_id IS NOT NULL AND receipt_id IS NULL AND receipt_item_id IS NULL
      AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL
      AND ((transaction_type='TRANSFER_OUT' AND change_qty<0) OR (transaction_type='TRANSFER_IN' AND change_qty>0))) OR
     (transfer_id IS NULL AND receipt_id IS NOT NULL AND receipt_item_id IS NOT NULL AND
      ((transaction_type IN ('RECEIPT','QUALITY_PASS','QUALITY_REJECT','PUTAWAY_IN') AND change_qty>0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL) OR
       (transaction_type IN ('QUALITY_OUT','PUTAWAY_OUT') AND change_qty<0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL) OR
       (transaction_type='REVERSAL' AND change_qty<0 AND reversed_transaction_id IS NOT NULL AND purchase_return_id IS NULL) OR
       (transaction_type='RETURN_OUT' AND change_qty<0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NOT NULL))))
)));
