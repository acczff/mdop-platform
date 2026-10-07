ALTER TABLE wms_inventory_balance
 ADD reserved_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
 ADD reservation_version BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_reservation_qty CHECK(reserved_qty>=0 AND available_qty+reserved_qty<=on_hand_qty);
ALTER TABLE wms_stock_count ADD reservation_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE wms_material_issue (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 demand_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 work_order_no VARCHAR(64) NOT NULL,
 warehouse_id BIGINT NOT NULL,
 target_warehouse_id BIGINT NOT NULL,
 material_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
 source_balance_id BIGINT NULL,
 target_location_id BIGINT NULL,
 target_balance_id BIGINT NULL,
 created_by VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 reserved_by VARCHAR(64) NULL,
 reserved_at DATETIME(6) NULL,
 closed_by VARCHAR(64) NULL,
 closed_at DATETIME(6) NULL,
 cancel_reason VARCHAR(500) NULL,
 FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id),
 FOREIGN KEY(target_warehouse_id) REFERENCES mdm_warehouse(id),
 FOREIGN KEY(material_id) REFERENCES mdm_material(id),
 FOREIGN KEY(source_balance_id) REFERENCES wms_inventory_balance(id),
 FOREIGN KEY(target_balance_id) REFERENCES wms_inventory_balance(id),
 FOREIGN KEY(target_location_id) REFERENCES mdm_location(id),
 CHECK(quantity>0 AND warehouse_id<>target_warehouse_id),
 CHECK(status IN ('OPEN','RESERVED','ISSUED','CANCELLED')),
 CHECK(status NOT IN ('RESERVED','ISSUED') OR (source_balance_id IS NOT NULL AND target_location_id IS NOT NULL AND reserved_by IS NOT NULL)),
 CHECK(status<>'ISSUED' OR (target_balance_id IS NOT NULL AND closed_by IS NOT NULL)),
 CHECK(status<>'CANCELLED' OR (cancel_reason IS NOT NULL AND closed_by IS NOT NULL)),
 INDEX ix_issue_warehouse(warehouse_id,target_warehouse_id,id)
);
CREATE TABLE wms_reservation_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 issue_id BIGINT NOT NULL,
 balance_id BIGINT NOT NULL,
 event_type VARCHAR(16) NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 before_reserved DECIMAL(18,6) NOT NULL,
 after_reserved DECIMAL(18,6) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(issue_id) REFERENCES wms_material_issue(id),
 FOREIGN KEY(balance_id) REFERENCES wms_inventory_balance(id),
 UNIQUE KEY uk_reservation_event(issue_id,event_type),
 CHECK(quantity>0 AND before_reserved>=0 AND after_reserved>=0),
 CHECK((event_type='RESERVE' AND after_reserved=before_reserved+quantity) OR
 (event_type IN ('RELEASE','CONSUME') AND after_reserved=before_reserved-quantity))
);
ALTER TABLE wms_inventory_transaction ADD issue_id BIGINT NULL,
 ADD CONSTRAINT fk_ledger_issue FOREIGN KEY(issue_id) REFERENCES wms_material_issue(id),
 ADD UNIQUE KEY uk_ledger_issue(issue_id,transaction_type);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK (
 (issue_id IS NULL AND (before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
    ((count_id IS NOT NULL AND transfer_id IS NULL AND receipt_id IS NULL AND receipt_item_id IS NULL AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL AND ((transaction_type='COUNT_GAIN' AND change_qty>0) OR (transaction_type='COUNT_LOSS' AND change_qty<0))) OR (count_id IS NULL AND ((transfer_id IS NOT NULL AND receipt_id IS NULL AND receipt_item_id IS NULL
      AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL
      AND ((transaction_type='TRANSFER_OUT' AND change_qty<0) OR (transaction_type='TRANSFER_IN' AND change_qty>0))) OR
     (transfer_id IS NULL AND receipt_id IS NOT NULL AND receipt_item_id IS NOT NULL AND
      ((transaction_type IN ('RECEIPT','QUALITY_PASS','QUALITY_REJECT','PUTAWAY_IN') AND change_qty>0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL) OR
       (transaction_type IN ('QUALITY_OUT','PUTAWAY_OUT') AND change_qty<0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL) OR
       (transaction_type='REVERSAL' AND change_qty<0 AND reversed_transaction_id IS NOT NULL AND purchase_return_id IS NULL) OR
       (transaction_type='RETURN_OUT' AND change_qty<0 AND reversed_transaction_id IS NULL AND purchase_return_id IS NOT NULL))))
)))) OR
 (issue_id IS NOT NULL AND count_id IS NULL AND transfer_id IS NULL AND receipt_id IS NULL
 AND receipt_item_id IS NULL AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL
 AND before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty
 AND ((transaction_type='ISSUE_OUT' AND change_qty<0) OR (transaction_type='ISSUE_IN' AND change_qty>0)))
);
