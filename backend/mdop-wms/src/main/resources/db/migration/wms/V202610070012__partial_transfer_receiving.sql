-- Keep legacy actions and ledger IDs; each historic full receipt becomes one receipt record.
ALTER TABLE wms_cross_transfer ADD received_qty DECIMAL(18,6) NOT NULL DEFAULT 0;
UPDATE wms_cross_transfer SET received_qty=quantity WHERE status='RECEIVED';
ALTER TABLE wms_cross_transfer DROP CHECK wms_cross_transfer_chk_6;
ALTER TABLE wms_cross_transfer ADD CONSTRAINT ck_cross_receipt_progress CHECK(
 (status='RECEIVED' AND received_qty=quantity AND target_balance_id IS NOT NULL AND received_by IS NOT NULL AND received_at IS NOT NULL)
 OR (status='IN_TRANSIT' AND received_qty>=0 AND received_qty<quantity AND received_by IS NULL AND received_at IS NULL AND
     ((received_qty=0 AND target_balance_id IS NULL) OR (received_qty>0 AND target_balance_id IS NOT NULL)))
 OR (status NOT IN ('IN_TRANSIT','RECEIVED') AND received_qty=0 AND target_balance_id IS NULL AND received_by IS NULL AND received_at IS NULL)
);

ALTER TABLE wms_cross_transfer_action ADD INDEX ix_cross_action_order(cross_transfer_id,id);
ALTER TABLE wms_cross_transfer_action DROP INDEX uk_cross_action;
ALTER TABLE wms_cross_transfer_action ADD single_action VARCHAR(16) GENERATED ALWAYS AS (IF(action_type='RECEIVE',NULL,action_type)) STORED,
 ADD UNIQUE KEY uk_cross_single_action(cross_transfer_id,single_action);

CREATE TABLE wms_cross_transfer_receipt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,cross_transfer_id BIGINT NOT NULL,action_id BIGINT NOT NULL UNIQUE,
 target_balance_id BIGINT NOT NULL,quantity DECIMAL(18,6) NOT NULL,
 cumulative_qty DECIMAL(18,6) NOT NULL,remaining_qty DECIMAL(18,6) NOT NULL,
 FOREIGN KEY(cross_transfer_id) REFERENCES wms_cross_transfer(id),
 FOREIGN KEY(action_id) REFERENCES wms_cross_transfer_action(id),
 FOREIGN KEY(target_balance_id) REFERENCES wms_inventory_balance(id),
 CHECK(quantity>0 AND cumulative_qty>=quantity AND remaining_qty>=0),
 INDEX ix_cross_receipt_order(cross_transfer_id,id)
);
INSERT INTO wms_cross_transfer_receipt(cross_transfer_id,action_id,target_balance_id,quantity,cumulative_qty,remaining_qty)
 SELECT d.id,a.id,d.target_balance_id,d.quantity,d.quantity,0 FROM wms_cross_transfer d
 JOIN wms_cross_transfer_action a ON a.cross_transfer_id=d.id AND a.action_type='RECEIVE' WHERE d.status='RECEIVED';

ALTER TABLE wms_inventory_transaction ADD cross_transfer_receipt_id BIGINT,
 ADD FOREIGN KEY(cross_transfer_receipt_id) REFERENCES wms_cross_transfer_receipt(id),
 ADD UNIQUE KEY uk_cross_receipt_ledger(cross_transfer_receipt_id),ADD INDEX ix_cross_ledger_order(cross_transfer_id);
UPDATE wms_inventory_transaction t JOIN wms_cross_transfer_receipt r ON r.cross_transfer_id=t.cross_transfer_id
 SET t.cross_transfer_receipt_id=r.id WHERE t.transaction_type='CROSS_IN';
ALTER TABLE wms_inventory_transaction DROP INDEX uk_cross_ledger;
ALTER TABLE wms_inventory_transaction ADD cross_out_order BIGINT GENERATED ALWAYS AS (IF(transaction_type='CROSS_OUT',cross_transfer_id,NULL)) STORED,
 ADD UNIQUE KEY uk_cross_out_once(cross_out_order),
 ADD CONSTRAINT ck_cross_receipt_ledger CHECK((transaction_type='CROSS_IN' AND cross_transfer_receipt_id IS NOT NULL) OR (transaction_type<>'CROSS_IN' AND cross_transfer_receipt_id IS NULL));
