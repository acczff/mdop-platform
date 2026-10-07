ALTER TABLE wms_inventory_balance MODIFY supplier_id BIGINT NULL,
 ADD origin_type VARCHAR(16) NOT NULL DEFAULT 'PURCHASE',
 ADD CONSTRAINT ck_stock_origin CHECK((origin_type='PURCHASE' AND supplier_id IS NOT NULL) OR (origin_type='PRODUCTION' AND supplier_id IS NULL));
CREATE TABLE wms_finished_receipt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, demand_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 request_hash CHAR(64) NOT NULL, work_order_no VARCHAR(64) NOT NULL, warehouse_id BIGINT NOT NULL, material_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL, batch_no VARCHAR(64) NOT NULL, date_code VARCHAR(32) NOT NULL DEFAULT '', production_date DATE NOT NULL, expiry_date DATE,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN', created_by VARCHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL,
 received_location_id BIGINT,received_balance_id BIGINT,received_by VARCHAR(64),received_at DATETIME(6),
 quality_event_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin UNIQUE,quality_status VARCHAR(16),quality_reason VARCHAR(500),quality_by VARCHAR(64),quality_at DATETIME(6),quality_balance_id BIGINT,
 stored_location_id BIGINT,stored_balance_id BIGINT,stored_by VARCHAR(64),stored_at DATETIME(6),
 FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id),FOREIGN KEY(material_id) REFERENCES mdm_material(id),
 FOREIGN KEY(received_location_id) REFERENCES mdm_location(id),FOREIGN KEY(stored_location_id) REFERENCES mdm_location(id),
 FOREIGN KEY(received_balance_id) REFERENCES wms_inventory_balance(id),FOREIGN KEY(quality_balance_id) REFERENCES wms_inventory_balance(id),FOREIGN KEY(stored_balance_id) REFERENCES wms_inventory_balance(id),
 CHECK(quantity>0),CHECK(status IN ('OPEN','RECEIVED','QUALIFIED','REJECTED','STORED')),
 CHECK(status='OPEN' OR (received_balance_id IS NOT NULL AND received_by IS NOT NULL)),
 CHECK(status NOT IN ('QUALIFIED','REJECTED','STORED') OR (quality_balance_id IS NOT NULL AND quality_status IN ('QUALIFIED','REJECTED'))),
 CHECK(status<>'STORED' OR (stored_balance_id IS NOT NULL AND stored_by IS NOT NULL)),
 INDEX ix_finished_warehouse(warehouse_id,id),INDEX ix_finished_order(work_order_no)
);
ALTER TABLE wms_outbox DROP CHECK ck_outbox_aggregate;
ALTER TABLE wms_outbox ADD finished_ref BIGINT GENERATED ALWAYS AS(IF(aggregate_type='FinishedReceipt',aggregate_id,NULL)) STORED,
 ADD CONSTRAINT fk_outbox_finished FOREIGN KEY(finished_ref) REFERENCES wms_finished_receipt(id),
 ADD CONSTRAINT ck_outbox_aggregate CHECK(aggregate_type IN ('Receipt','MaterialIssue','FinishedReceipt'));
ALTER TABLE wms_inventory_transaction ADD finished_receipt_id BIGINT,
 ADD CONSTRAINT fk_ledger_finished FOREIGN KEY(finished_receipt_id) REFERENCES wms_finished_receipt(id),
 ADD UNIQUE KEY uk_ledger_finished(finished_receipt_id,transaction_type);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK(
 (finished_receipt_id IS NULL AND ((production_reversal_id IS NULL AND ((consumption_id IS NULL AND production_return_id IS NULL AND ((issue_id IS NULL AND (before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
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
 AND ((transaction_type IN ('PRC_RESTORE','PRR_RESTORE') AND change_qty>0) OR (transaction_type='PRR_REMOVE' AND change_qty<0))))) OR
 (finished_receipt_id IS NOT NULL AND production_reversal_id IS NULL AND reversed_transaction_id IS NULL AND receipt_id IS NULL AND receipt_item_id IS NULL AND purchase_return_id IS NULL AND transfer_id IS NULL AND count_id IS NULL AND issue_id IS NULL AND consumption_id IS NULL AND production_return_id IS NULL AND before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty
 AND ((transaction_type IN ('FG_RECEIPT','FG_QC_IN','FG_PUT_IN') AND change_qty>0) OR (transaction_type IN ('FG_QC_OUT','FG_PUT_OUT') AND change_qty<0))))
;
