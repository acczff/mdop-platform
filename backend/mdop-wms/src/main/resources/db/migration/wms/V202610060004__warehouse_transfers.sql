CREATE TABLE wms_stock_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    source_balance_id BIGINT NOT NULL,
    target_balance_id BIGINT NOT NULL,
    quantity DECIMAL(18,6) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    confirmed_by VARCHAR(64) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id),
    FOREIGN KEY(source_balance_id) REFERENCES wms_inventory_balance(id),
    FOREIGN KEY(target_balance_id) REFERENCES wms_inventory_balance(id),
    CHECK(quantity>0 AND source_balance_id<>target_balance_id),
    INDEX ix_transfer_warehouse(warehouse_id,id)
);
ALTER TABLE wms_inventory_transaction
    MODIFY receipt_id BIGINT NULL,
    MODIFY receipt_item_id BIGINT NULL,
    ADD transfer_id BIGINT NULL,
    ADD CONSTRAINT fk_ledger_transfer FOREIGN KEY(transfer_id) REFERENCES wms_stock_transfer(id),
    ADD UNIQUE KEY uk_ledger_transfer(transfer_id,transaction_type);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK (
    before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
    ((transfer_id IS NOT NULL AND receipt_id IS NULL AND receipt_item_id IS NULL
      AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL
      AND ((transaction_type='TRANSFER_OUT' AND change_qty<0) OR (transaction_type='TRANSFER_IN' AND change_qty>0))) OR
     (transfer_id IS NULL AND receipt_id IS NOT NULL AND receipt_item_id IS NOT NULL AND
      ((transaction_type IN ('RECEIPT','QUALITY_PASS','QUALITY_REJECT','PUTAWAY_IN') AND change_qty>0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL) OR
       (transaction_type IN ('QUALITY_OUT','PUTAWAY_OUT') AND change_qty<0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL) OR
       (transaction_type='REVERSAL' AND change_qty<0 AND reversed_transaction_id IS NOT NULL AND purchase_return_id IS NULL) OR
       (transaction_type='RETURN_OUT' AND change_qty<0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NOT NULL))))
);
