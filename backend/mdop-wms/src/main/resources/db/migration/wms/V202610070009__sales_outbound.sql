CREATE TABLE wms_sales_order (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,demand_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 sales_order_no VARCHAR(64) NOT NULL,customer_reference VARCHAR(128) NOT NULL,warehouse_id BIGINT NOT NULL,material_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'OPEN',source_balance_id BIGINT,
 created_by VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,reserved_by VARCHAR(64),reserved_at DATETIME(6),
 pick_id BIGINT,picked_by VARCHAR(64),picked_at DATETIME(6),reviewed_by VARCHAR(64),reviewed_at DATETIME(6),
 closed_by VARCHAR(64),closed_at DATETIME(6),cancel_reason VARCHAR(500),
 FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id),FOREIGN KEY(material_id) REFERENCES mdm_material(id),FOREIGN KEY(source_balance_id) REFERENCES wms_inventory_balance(id),
 CHECK(quantity>0),CHECK(status IN ('OPEN','RESERVED','PICKED','VERIFIED','SHIPPED','CANCELLED')),
 CHECK(status NOT IN ('RESERVED','PICKED','VERIFIED','SHIPPED') OR source_balance_id IS NOT NULL),
 CHECK(status NOT IN ('PICKED','VERIFIED','SHIPPED') OR (pick_id IS NOT NULL AND picked_by IS NOT NULL)),
 CHECK(status NOT IN ('VERIFIED','SHIPPED') OR (reviewed_by IS NOT NULL AND reviewed_by<>picked_by)),
 CHECK(status NOT IN ('SHIPPED','CANCELLED') OR closed_by IS NOT NULL),
 INDEX ix_sales_warehouse(warehouse_id,id)
);
CREATE TABLE wms_sales_action (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,sales_order_id BIGINT NOT NULL,request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 action_type VARCHAR(16) NOT NULL,payload_hash CHAR(64) NOT NULL,pick_id BIGINT,reason VARCHAR(500),created_by VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(sales_order_id) REFERENCES wms_sales_order(id),CHECK(action_type IN ('PICK','APPROVE','REJECT')),
 UNIQUE KEY uk_sales_review(pick_id)
);
ALTER TABLE wms_sales_order ADD FOREIGN KEY(pick_id) REFERENCES wms_sales_action(id);
ALTER TABLE wms_reservation_event MODIFY issue_id BIGINT NULL, ADD sales_order_id BIGINT,
 ADD FOREIGN KEY(sales_order_id) REFERENCES wms_sales_order(id),ADD UNIQUE KEY uk_sales_reservation(sales_order_id,event_type),
 ADD CONSTRAINT ck_reservation_source CHECK((issue_id IS NOT NULL AND sales_order_id IS NULL) OR (issue_id IS NULL AND sales_order_id IS NOT NULL));
ALTER TABLE wms_outbox DROP CHECK ck_outbox_aggregate;
ALTER TABLE wms_outbox ADD sales_ref BIGINT GENERATED ALWAYS AS(IF(aggregate_type='SalesOrder',aggregate_id,NULL)) STORED,
 ADD FOREIGN KEY(sales_ref) REFERENCES wms_sales_order(id),
 ADD CONSTRAINT ck_outbox_aggregate CHECK(aggregate_type IN ('Receipt','MaterialIssue','FinishedReceipt','SalesOrder'));
ALTER TABLE wms_inventory_transaction ADD sales_order_id BIGINT,ADD FOREIGN KEY(sales_order_id) REFERENCES wms_sales_order(id),ADD UNIQUE KEY uk_sales_ledger(sales_order_id);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK(
(sales_order_id IS NULL AND ((finished_receipt_id IS NULL AND ((production_reversal_id IS NULL AND ((consumption_id IS NULL AND production_return_id IS NULL AND ((issue_id IS NULL AND (before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND
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
 AND ((transaction_type IN ('FG_RECEIPT','FG_QC_IN','FG_PUT_IN') AND change_qty>0) OR (transaction_type IN ('FG_QC_OUT','FG_PUT_OUT') AND change_qty<0))))) OR
(sales_order_id IS NOT NULL AND transaction_type='SALES_OUT' AND change_qty<0 AND before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty
 AND finished_receipt_id IS NULL AND production_reversal_id IS NULL AND reversed_transaction_id IS NULL AND receipt_id IS NULL AND receipt_item_id IS NULL AND purchase_return_id IS NULL AND transfer_id IS NULL AND count_id IS NULL AND issue_id IS NULL AND consumption_id IS NULL AND production_return_id IS NULL));
