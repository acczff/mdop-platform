CREATE TABLE wms_production_reversal (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, issue_id BIGINT NOT NULL,
 consumption_id BIGINT NULL, production_return_id BIGINT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 external_reference VARCHAR(64) NOT NULL, reason VARCHAR(500) NOT NULL,
 quantity DECIMAL(18,6) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 created_by VARCHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL,
 reviewed_by VARCHAR(64), reviewed_at DATETIME(6), decision_reason VARCHAR(500),
 active_consumption BIGINT GENERATED ALWAYS AS(IF(status IN ('PENDING','APPROVED'),consumption_id,NULL)) STORED UNIQUE,
 active_return BIGINT GENERATED ALWAYS AS(IF(status IN ('PENDING','APPROVED'),production_return_id,NULL)) STORED UNIQUE,
 FOREIGN KEY(issue_id) REFERENCES wms_material_issue(id),
 FOREIGN KEY(consumption_id) REFERENCES wms_production_consumption(id),
 FOREIGN KEY(production_return_id) REFERENCES wms_production_return(id),
 CHECK((consumption_id IS NULL)<>(production_return_id IS NULL)), CHECK(quantity>0),
 CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
 CHECK(status NOT IN ('APPROVED','REJECTED') OR (reviewed_by IS NOT NULL AND reviewed_by<>created_by AND reviewed_at IS NOT NULL)),
 INDEX ix_prod_reversal(issue_id,id)
);
ALTER TABLE wms_inventory_transaction ADD production_reversal_id BIGINT NULL,
 ADD CONSTRAINT fk_ledger_prod_reverse FOREIGN KEY(production_reversal_id) REFERENCES wms_production_reversal(id),
 ADD UNIQUE KEY uk_prod_reverse(production_reversal_id,transaction_type);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK(
 (production_reversal_id IS NULL AND ((consumption_id IS NULL AND production_return_id IS NULL AND ((issue_id IS NULL AND (before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
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
)) OR
 (production_reversal_id IS NOT NULL AND reversed_transaction_id IS NOT NULL AND receipt_id IS NULL AND receipt_item_id IS NULL AND purchase_return_id IS NULL AND transfer_id IS NULL AND count_id IS NULL AND issue_id IS NULL AND consumption_id IS NULL AND production_return_id IS NULL AND before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty
 AND ((transaction_type IN ('PRC_RESTORE','PRR_RESTORE') AND change_qty>0) OR (transaction_type='PRR_REMOVE' AND change_qty<0))))
;
