ALTER TABLE wms_inventory_balance ADD production_qty DECIMAL(18,6) NOT NULL DEFAULT 0;
UPDATE wms_inventory_balance b JOIN
 (SELECT target_balance_id,SUM(quantity) AS qty FROM wms_material_issue WHERE status='ISSUED' GROUP BY target_balance_id) d ON d.target_balance_id=b.id
SET b.production_qty=d.qty,b.available_qty=b.available_qty-d.qty;
ALTER TABLE wms_inventory_balance ADD CONSTRAINT ck_production_qty CHECK(production_qty>=0 AND available_qty+reserved_qty+production_qty<=on_hand_qty);
ALTER TABLE wms_material_issue ADD consumed_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
 ADD returned_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_issue_usage CHECK(consumed_qty>=0 AND returned_qty>=0 AND consumed_qty+returned_qty<=quantity);
CREATE TABLE wms_production_consumption (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 event_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 issue_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 reported_by VARCHAR(64) NOT NULL,
 reported_at DATETIME(6) NOT NULL,
 FOREIGN KEY(issue_id) REFERENCES wms_material_issue(id),
 CHECK(quantity>0)
);
CREATE TABLE wms_production_return (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 event_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 issue_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 quality_status VARCHAR(16) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 target_location_id BIGINT NOT NULL,
 target_balance_id BIGINT NULL,
 requested_by VARCHAR(64) NOT NULL,
 requested_at DATETIME(6) NOT NULL,
 closed_by VARCHAR(64) NULL,
 closed_at DATETIME(6) NULL,
 cancel_reason VARCHAR(500) NULL,
 FOREIGN KEY(issue_id) REFERENCES wms_material_issue(id),
 FOREIGN KEY(target_location_id) REFERENCES mdm_location(id),
 FOREIGN KEY(target_balance_id) REFERENCES wms_inventory_balance(id),
 CHECK(quantity>0 AND quality_status IN ('QUALIFIED','REJECTED')),
 CHECK(status IN ('PENDING','RETURNED','CANCELLED')),
 CHECK(status<>'RETURNED' OR (target_balance_id IS NOT NULL AND closed_by IS NOT NULL AND closed_at IS NOT NULL)),
 CHECK(status<>'CANCELLED' OR (cancel_reason IS NOT NULL AND closed_by IS NOT NULL AND closed_at IS NOT NULL)),
 INDEX ix_production_return_issue(issue_id,status)
);
ALTER TABLE wms_inventory_transaction ADD consumption_id BIGINT NULL,
 ADD production_return_id BIGINT NULL,
 ADD CONSTRAINT fk_ledger_consumption FOREIGN KEY(consumption_id) REFERENCES wms_production_consumption(id),
 ADD CONSTRAINT fk_ledger_production_return FOREIGN KEY(production_return_id) REFERENCES wms_production_return(id),
 ADD UNIQUE KEY uk_ledger_consumption(consumption_id),
 ADD UNIQUE KEY uk_ledger_production_return(production_return_id,transaction_type);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK(
 (consumption_id IS NULL AND production_return_id IS NULL AND ((issue_id IS NULL AND (before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
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
)) OR
 (issue_id IS NULL AND count_id IS NULL AND transfer_id IS NULL AND receipt_id IS NULL AND receipt_item_id IS NULL
 AND reversed_transaction_id IS NULL AND purchase_return_id IS NULL AND before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty
 AND ((consumption_id IS NOT NULL AND production_return_id IS NULL AND transaction_type='CONSUMPTION' AND change_qty<0) OR
 (consumption_id IS NULL AND production_return_id IS NOT NULL AND ((transaction_type='PROD_RETURN_OUT' AND change_qty<0) OR (transaction_type='PROD_RETURN_IN' AND change_qty>0)))))
);
