-- Preserve concrete foreign keys for both supported aggregate types.
ALTER TABLE wms_outbox DROP FOREIGN KEY wms_outbox_ibfk_1;
ALTER TABLE wms_outbox
 ADD receipt_ref BIGINT GENERATED ALWAYS AS (CASE WHEN aggregate_type='Receipt' THEN aggregate_id ELSE NULL END) STORED,
 ADD issue_ref BIGINT GENERATED ALWAYS AS (CASE WHEN aggregate_type='MaterialIssue' THEN aggregate_id ELSE NULL END) STORED,
 ADD CONSTRAINT fk_outbox_receipt FOREIGN KEY(receipt_ref) REFERENCES wms_receipt(id),
 ADD CONSTRAINT fk_outbox_issue FOREIGN KEY(issue_ref) REFERENCES wms_material_issue(id),
 ADD CONSTRAINT ck_outbox_aggregate CHECK(aggregate_type IN ('Receipt','MaterialIssue'));
ALTER TABLE wms_outbox DROP INDEX uk_outbox_business,
 ADD UNIQUE KEY uk_outbox_business(aggregate_type,aggregate_id,event_type,business_key);

-- Existing issued documents also need a feedback event; do not repeat inventory posting.
INSERT INTO wms_outbox(message_id,event_type,aggregate_type,aggregate_id,trace_id,payload,occurred_at)
SELECT UUID(),'MaterialIssued','MaterialIssue',d.id,UUID(),
 JSON_OBJECT('issueId',d.id,'workOrderNo',d.work_order_no,'businessKey','',
 'quantity',CAST(d.quantity AS CHAR),'materialId',d.material_id,'batchNo',b.batch_no,
 'sourceWarehouseId',d.warehouse_id,'lineWarehouseId',d.target_warehouse_id),d.closed_at
FROM wms_material_issue d JOIN wms_inventory_balance b ON b.id=d.source_balance_id
WHERE d.status='ISSUED';
