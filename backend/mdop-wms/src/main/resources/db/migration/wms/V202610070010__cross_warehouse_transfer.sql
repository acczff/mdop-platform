CREATE TABLE wms_cross_transfer (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 source_warehouse_id BIGINT NOT NULL,target_warehouse_id BIGINT NOT NULL,
 source_balance_id BIGINT NOT NULL,target_location_id BIGINT NOT NULL,target_balance_id BIGINT,
 quantity DECIMAL(18,6) NOT NULL,reason VARCHAR(500) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,request_hash CHAR(64) NOT NULL,
 created_by VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 reviewed_by VARCHAR(64),reviewed_at DATETIME(6),shipped_by VARCHAR(64),shipped_at DATETIME(6),
 received_by VARCHAR(64),received_at DATETIME(6),
 FOREIGN KEY(source_warehouse_id) REFERENCES mdm_warehouse(id),FOREIGN KEY(target_warehouse_id) REFERENCES mdm_warehouse(id),
 FOREIGN KEY(source_balance_id) REFERENCES wms_inventory_balance(id),FOREIGN KEY(target_balance_id) REFERENCES wms_inventory_balance(id),
 FOREIGN KEY(target_location_id) REFERENCES mdm_location(id),
 CHECK(quantity>0 AND source_warehouse_id<>target_warehouse_id),
 CHECK(status IN ('PENDING','APPROVED','IN_TRANSIT','RECEIVED','REJECTED','CANCELLED')),
 CHECK(reviewed_by IS NULL OR reviewed_by<>created_by),
 CHECK(status NOT IN ('APPROVED','IN_TRANSIT','RECEIVED','REJECTED') OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
 CHECK((status IN ('IN_TRANSIT','RECEIVED') AND shipped_by IS NOT NULL AND shipped_at IS NOT NULL) OR (status NOT IN ('IN_TRANSIT','RECEIVED') AND shipped_by IS NULL AND shipped_at IS NULL)),
 CHECK((status='RECEIVED' AND target_balance_id IS NOT NULL AND received_by IS NOT NULL AND received_at IS NOT NULL) OR (status<>'RECEIVED' AND target_balance_id IS NULL AND received_by IS NULL AND received_at IS NULL)),
 INDEX ix_cross_source(source_warehouse_id,id),INDEX ix_cross_target(target_warehouse_id,id)
);
CREATE TABLE wms_cross_transfer_action (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,cross_transfer_id BIGINT NOT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,payload_hash CHAR(64) NOT NULL,
 action_type VARCHAR(16) NOT NULL,reason VARCHAR(500) NOT NULL,created_by VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(cross_transfer_id) REFERENCES wms_cross_transfer(id),
 CHECK(action_type IN ('CREATE','APPROVE','REJECT','CANCEL','SHIP','RECEIVE')),
 UNIQUE KEY uk_cross_action(cross_transfer_id,action_type)
);
ALTER TABLE wms_reservation_event DROP CHECK ck_reservation_source;
ALTER TABLE wms_reservation_event ADD cross_transfer_id BIGINT,
 ADD FOREIGN KEY(cross_transfer_id) REFERENCES wms_cross_transfer(id),ADD UNIQUE KEY uk_cross_reservation(cross_transfer_id,event_type),
 ADD CONSTRAINT ck_reservation_source CHECK((issue_id IS NOT NULL)+(sales_order_id IS NOT NULL)+(cross_transfer_id IS NOT NULL)=1);
ALTER TABLE wms_inventory_transaction ADD cross_transfer_id BIGINT,
 ADD FOREIGN KEY(cross_transfer_id) REFERENCES wms_cross_transfer(id),ADD UNIQUE KEY uk_cross_ledger(cross_transfer_id,transaction_type);
ALTER TABLE wms_inventory_transaction DROP CHECK ck_ledger_signed;
ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_ledger_signed CHECK((cross_transfer_id IS NULL AND (
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
 AND finished_receipt_id IS NULL AND production_reversal_id IS NULL AND reversed_transaction_id IS NULL AND receipt_id IS NULL AND receipt_item_id IS NULL AND purchase_return_id IS NULL AND transfer_id IS NULL AND count_id IS NULL AND issue_id IS NULL AND consumption_id IS NULL AND production_return_id IS NULL))) OR (cross_transfer_id IS NOT NULL AND sales_order_id IS NULL AND finished_receipt_id IS NULL AND production_reversal_id IS NULL AND reversed_transaction_id IS NULL AND receipt_id IS NULL AND receipt_item_id IS NULL AND purchase_return_id IS NULL AND transfer_id IS NULL AND count_id IS NULL AND issue_id IS NULL AND consumption_id IS NULL AND production_return_id IS NULL AND before_qty>=0 AND after_qty>=0 AND after_qty=before_qty+change_qty AND ((transaction_type='CROSS_OUT' AND change_qty<0) OR (transaction_type='CROSS_IN' AND change_qty>0))));
