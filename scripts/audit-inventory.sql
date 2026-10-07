-- Read-only inventory reconciliation including transfers, counts, material issues and production usage.
-- Every mismatch_count must be zero, including reservations, production allocations and all paired movements.
SELECT 'balance_vs_ledger' AS check_name,COUNT(*) AS mismatch_count
FROM wms_inventory_balance b LEFT JOIN
 (SELECT balance_id,SUM(change_qty) AS quantity FROM wms_inventory_transaction GROUP BY balance_id) t ON t.balance_id=b.id
WHERE b.on_hand_qty<>COALESCE(t.quantity,0)
UNION ALL
SELECT 'ledger_chain',COUNT(*) FROM
 (SELECT before_qty,COALESCE(LAG(after_qty) OVER(PARTITION BY balance_id ORDER BY id),0) AS previous_qty
  FROM wms_inventory_transaction) chain WHERE before_qty<>previous_qty
UNION ALL
SELECT 'quality_availability',COUNT(*) FROM wms_inventory_balance b JOIN mdm_location l ON l.id=b.location_id
WHERE b.available_qty<>CASE WHEN b.quality_status='QUALIFIED' AND l.area_type='STORAGE' THEN b.on_hand_qty-b.reserved_qty-b.production_qty ELSE 0 END
UNION ALL
SELECT 'arrival_received_total',COUNT(*) FROM wms_arrival_notice_item a LEFT JOIN
 (SELECT i.arrival_item_id,SUM(i.quantity) AS quantity FROM wms_receipt_item i JOIN wms_receipt r ON r.id=i.receipt_id
  WHERE r.status='SUBMITTED' AND r.correction_status='NONE' GROUP BY i.arrival_item_id) x ON x.arrival_item_id=a.id
WHERE a.received_qty<>COALESCE(x.quantity,0)
UNION ALL
SELECT 'quality_quantity',COUNT(*) FROM wms_quality_item q JOIN wms_receipt_item i ON i.id=q.receipt_item_id
WHERE q.qualified_qty+q.rejected_qty<>i.quantity
UNION ALL
SELECT 'return_ledger',COUNT(*) FROM wms_purchase_return p LEFT JOIN wms_inventory_transaction t ON t.purchase_return_id=p.id
WHERE (p.status='RETURNED' AND (t.id IS NULL OR t.transaction_type<>'RETURN_OUT' OR t.change_qty<>-p.quantity))
 OR (p.status<>'RETURNED' AND t.id IS NOT NULL)
UNION ALL
SELECT 'return_outbox',COUNT(*) FROM wms_purchase_return p LEFT JOIN wms_outbox o
 ON o.event_type='PurchaseReturnConfirmed' AND o.aggregate_id=p.receipt_id AND o.business_key=CAST(p.id AS CHAR)
WHERE (p.status='RETURNED' AND o.message_id IS NULL) OR (p.status<>'RETURNED' AND o.message_id IS NOT NULL)
UNION ALL
SELECT 'return_quota',COUNT(*) FROM wms_quality_item q JOIN
 (SELECT receipt_item_id,SUM(quantity) AS quantity FROM wms_purchase_return
  WHERE status IN ('PENDING','APPROVED','RETURNED') GROUP BY receipt_item_id) p ON p.receipt_item_id=q.receipt_item_id
WHERE p.quantity>q.rejected_qty
UNION ALL
SELECT 'transfer_pair',COUNT(*) FROM wms_stock_transfer x LEFT JOIN
 (SELECT transfer_id,COUNT(*) AS n,SUM(change_qty) AS net FROM wms_inventory_transaction
  WHERE transfer_id IS NOT NULL GROUP BY transfer_id) t ON t.transfer_id=x.id
WHERE COALESCE(t.n,0)<>2 OR t.net<>0
 OR NOT EXISTS(SELECT 1 FROM wms_inventory_transaction l WHERE l.transfer_id=x.id AND l.transaction_type='TRANSFER_OUT' AND l.balance_id=x.source_balance_id AND l.change_qty=-x.quantity)
 OR NOT EXISTS(SELECT 1 FROM wms_inventory_transaction l WHERE l.transfer_id=x.id AND l.transaction_type='TRANSFER_IN' AND l.balance_id=x.target_balance_id AND l.change_qty=x.quantity)
UNION ALL
SELECT 'transfer_dimensions',COUNT(*) FROM wms_stock_transfer x
 JOIN wms_inventory_balance s ON s.id=x.source_balance_id
 JOIN wms_inventory_balance d ON d.id=x.target_balance_id
WHERE x.warehouse_id<>s.warehouse_id OR s.warehouse_id<>d.warehouse_id
 OR s.location_id=d.location_id OR s.material_id<>d.material_id OR s.supplier_id<>d.supplier_id
 OR s.batch_no<>d.batch_no OR s.date_code<>d.date_code
 OR NOT(s.production_date<=>d.production_date) OR NOT(s.expiry_date<=>d.expiry_date)
 OR s.quality_status<>d.quality_status OR s.owner_type<>d.owner_type OR s.owner_id<>d.owner_id
UNION ALL
SELECT 'count_ledger',COUNT(*) FROM wms_stock_count c LEFT JOIN wms_inventory_transaction t ON t.count_id=c.id
WHERE (c.status='APPROVED' AND c.counted_qty<>c.snapshot_qty AND
 (t.id IS NULL OR t.balance_id<>c.balance_id OR t.before_qty<>c.snapshot_qty OR t.after_qty<>c.counted_qty
 OR t.change_qty<>c.counted_qty-c.snapshot_qty OR t.transaction_type<>CASE WHEN c.counted_qty>c.snapshot_qty THEN 'COUNT_GAIN' ELSE 'COUNT_LOSS' END))
 OR ((c.status<>'APPROVED' OR c.counted_qty=c.snapshot_qty) AND t.id IS NOT NULL)
UNION ALL
SELECT 'count_review',COUNT(*) FROM wms_stock_count c JOIN wms_inventory_balance b ON b.id=c.balance_id
WHERE c.warehouse_id<>b.warehouse_id OR (c.status='APPROVED' AND (c.reviewed_by IS NULL OR c.reviewed_by=c.created_by OR c.reviewed_at IS NULL))
UNION ALL
SELECT 'reservation_balance',COUNT(*) FROM wms_inventory_balance b LEFT JOIN
 (SELECT source_balance_id,SUM(quantity) AS qty FROM wms_material_issue WHERE status='RESERVED' GROUP BY source_balance_id) d ON d.source_balance_id=b.id
WHERE b.reserved_qty<>COALESCE(d.qty,0)
UNION ALL
SELECT 'reservation_events',COUNT(*) FROM wms_material_issue d
WHERE (d.source_balance_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM wms_reservation_event e WHERE e.issue_id=d.id AND e.event_type='RESERVE' AND e.balance_id=d.source_balance_id AND e.quantity=d.quantity))
 OR (d.status='ISSUED' AND NOT EXISTS(SELECT 1 FROM wms_reservation_event e WHERE e.issue_id=d.id AND e.event_type='CONSUME' AND e.quantity=d.quantity))
 OR (d.status='CANCELLED' AND d.source_balance_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM wms_reservation_event e WHERE e.issue_id=d.id AND e.event_type='RELEASE' AND e.quantity=d.quantity))
 OR (SELECT COUNT(*) FROM wms_reservation_event e WHERE e.issue_id=d.id)<>CASE WHEN d.status='OPEN' OR d.source_balance_id IS NULL THEN 0 WHEN d.status='RESERVED' THEN 1 ELSE 2 END
UNION ALL
SELECT 'reservation_chain',COUNT(*) FROM
 (SELECT before_reserved,COALESCE(LAG(after_reserved) OVER(PARTITION BY balance_id ORDER BY id),0) AS previous_qty FROM wms_reservation_event) chain
WHERE before_reserved<>previous_qty
UNION ALL
SELECT 'issue_pair',COUNT(*) FROM wms_material_issue d LEFT JOIN
 (SELECT issue_id,COUNT(*) AS n,SUM(change_qty) AS net FROM wms_inventory_transaction WHERE issue_id IS NOT NULL GROUP BY issue_id) t ON t.issue_id=d.id
WHERE (d.status='ISSUED' AND (COALESCE(t.n,0)<>2 OR t.net<>0
 OR NOT EXISTS(SELECT 1 FROM wms_inventory_transaction l WHERE l.issue_id=d.id AND l.transaction_type='ISSUE_OUT' AND l.balance_id=d.source_balance_id AND l.change_qty=-d.quantity)
 OR NOT EXISTS(SELECT 1 FROM wms_inventory_transaction l WHERE l.issue_id=d.id AND l.transaction_type='ISSUE_IN' AND l.balance_id=d.target_balance_id AND l.change_qty=d.quantity)))
 OR (d.status<>'ISSUED' AND COALESCE(t.n,0)<>0)
UNION ALL
SELECT 'issue_dimensions',COUNT(*) FROM wms_material_issue d JOIN wms_inventory_balance s ON s.id=d.source_balance_id JOIN wms_inventory_balance t ON t.id=d.target_balance_id
WHERE s.warehouse_id<>d.warehouse_id OR t.warehouse_id<>d.target_warehouse_id OR t.location_id<>d.target_location_id OR s.material_id<>d.material_id OR s.material_id<>t.material_id OR s.supplier_id<>t.supplier_id
 OR s.batch_no<>t.batch_no OR s.date_code<>t.date_code OR NOT(s.production_date<=>t.production_date) OR NOT(s.expiry_date<=>t.expiry_date)
 OR s.quality_status<>t.quality_status OR s.owner_type<>t.owner_type OR s.owner_id<>t.owner_id
UNION ALL
SELECT 'production_allocation',COUNT(*) FROM wms_inventory_balance b LEFT JOIN
 (SELECT target_balance_id,SUM(quantity-consumed_qty-returned_qty) AS qty FROM wms_material_issue WHERE status='ISSUED' GROUP BY target_balance_id) d ON d.target_balance_id=b.id
WHERE b.production_qty<>COALESCE(d.qty,0)
UNION ALL
SELECT 'consumption_total',COUNT(*) FROM wms_material_issue d LEFT JOIN
 (SELECT c.issue_id,SUM(c.quantity) AS qty FROM wms_production_consumption c WHERE NOT EXISTS(SELECT 1 FROM wms_production_reversal r WHERE r.consumption_id=c.id AND r.status='APPROVED') GROUP BY c.issue_id) c ON c.issue_id=d.id
WHERE d.consumed_qty<>COALESCE(c.qty,0)
UNION ALL
SELECT 'production_return_total',COUNT(*) FROM wms_material_issue d LEFT JOIN
 (SELECT p.issue_id,SUM(p.quantity) AS qty FROM wms_production_return p WHERE p.status='RETURNED' AND NOT EXISTS(SELECT 1 FROM wms_production_reversal r WHERE r.production_return_id=p.id AND r.status='APPROVED') GROUP BY p.issue_id) p ON p.issue_id=d.id
WHERE d.returned_qty<>COALESCE(p.qty,0)
UNION ALL
SELECT 'pending_return_quota',COUNT(*) FROM wms_material_issue d JOIN
 (SELECT issue_id,SUM(quantity) AS qty FROM wms_production_return WHERE status='PENDING' GROUP BY issue_id) p ON p.issue_id=d.id
WHERE p.qty>d.quantity-d.consumed_qty-d.returned_qty
UNION ALL
SELECT 'consumption_ledger',COUNT(*) FROM wms_production_consumption c JOIN wms_material_issue d ON d.id=c.issue_id
 LEFT JOIN wms_inventory_transaction t ON t.consumption_id=c.id
WHERE t.id IS NULL OR t.balance_id<>d.target_balance_id OR t.transaction_type<>'CONSUMPTION' OR t.change_qty<>-c.quantity
UNION ALL
SELECT 'production_return_pair',COUNT(*) FROM wms_production_return p JOIN wms_material_issue d ON d.id=p.issue_id LEFT JOIN
 (SELECT production_return_id,COUNT(*) AS n,SUM(change_qty) AS net FROM wms_inventory_transaction WHERE production_return_id IS NOT NULL GROUP BY production_return_id) t ON t.production_return_id=p.id
WHERE (p.status='RETURNED' AND (COALESCE(t.n,0)<>2 OR t.net<>0
 OR NOT EXISTS(SELECT 1 FROM wms_inventory_transaction l WHERE l.production_return_id=p.id AND l.transaction_type='PROD_RETURN_OUT' AND l.balance_id=d.target_balance_id AND l.change_qty=-p.quantity)
 OR NOT EXISTS(SELECT 1 FROM wms_inventory_transaction l WHERE l.production_return_id=p.id AND l.transaction_type='PROD_RETURN_IN' AND l.balance_id=p.target_balance_id AND l.change_qty=p.quantity)))
 OR (p.status<>'RETURNED' AND COALESCE(t.n,0)<>0)
UNION ALL
SELECT 'production_return_dimensions',COUNT(*) FROM wms_production_return p JOIN wms_material_issue d ON d.id=p.issue_id
 JOIN wms_inventory_balance s ON s.id=d.target_balance_id JOIN wms_inventory_balance t ON t.id=p.target_balance_id JOIN mdm_location l ON l.id=t.location_id
WHERE t.warehouse_id<>d.warehouse_id OR t.location_id<>p.target_location_id OR t.quality_status<>p.quality_status
 OR l.area_type<>CASE WHEN p.quality_status='QUALIFIED' THEN 'STORAGE' ELSE 'INSPECTION' END
 OR s.material_id<>t.material_id OR s.supplier_id<>t.supplier_id OR s.batch_no<>t.batch_no OR s.date_code<>t.date_code
 OR NOT(s.production_date<=>t.production_date) OR NOT(s.expiry_date<=>t.expiry_date) OR s.owner_type<>t.owner_type OR s.owner_id<>t.owner_id
UNION ALL
SELECT 'issue_feedback',COUNT(*) FROM wms_material_issue d LEFT JOIN wms_outbox o ON o.aggregate_type='MaterialIssue' AND o.aggregate_id=d.id AND o.event_type='MaterialIssued' AND o.business_key=''
WHERE (d.status='ISSUED' AND o.message_id IS NULL) OR (d.status<>'ISSUED' AND o.message_id IS NOT NULL)
UNION ALL
SELECT 'consumption_feedback',COUNT(*) FROM wms_production_consumption c LEFT JOIN wms_outbox o ON o.aggregate_type='MaterialIssue' AND o.aggregate_id=c.issue_id AND o.event_type='ProductionConsumed' AND o.business_key=CAST(c.id AS CHAR)
WHERE o.message_id IS NULL
UNION ALL
SELECT 'production_return_feedback',COUNT(*) FROM wms_production_return p LEFT JOIN wms_outbox o ON o.aggregate_type='MaterialIssue' AND o.aggregate_id=p.issue_id AND o.event_type='ProductionMaterialReturned' AND o.business_key=CAST(p.id AS CHAR)
WHERE (p.status='RETURNED' AND o.message_id IS NULL) OR (p.status<>'RETURNED' AND o.message_id IS NOT NULL)
UNION ALL
SELECT 'production_reversal_ledger',COUNT(*) FROM wms_production_reversal r LEFT JOIN
 (SELECT production_reversal_id,COUNT(*) AS n,SUM(change_qty) AS qty FROM wms_inventory_transaction WHERE production_reversal_id IS NOT NULL GROUP BY production_reversal_id)t ON t.production_reversal_id=r.id
WHERE (r.status='APPROVED' AND (COALESCE(t.n,0)<>IF(r.consumption_id IS NULL,2,1) OR t.qty<>IF(r.consumption_id IS NULL,0,r.quantity))) OR (r.status<>'APPROVED' AND COALESCE(t.n,0)<>0)
UNION ALL
SELECT 'production_reversal_links',COUNT(*) FROM wms_inventory_transaction t JOIN wms_inventory_transaction o ON o.id=t.reversed_transaction_id WHERE t.production_reversal_id IS NOT NULL AND (t.balance_id<>o.balance_id OR t.change_qty<>-o.change_qty)
UNION ALL
SELECT 'production_reversal_feedback',COUNT(*) FROM wms_production_reversal r LEFT JOIN wms_outbox o ON o.aggregate_type='MaterialIssue' AND o.aggregate_id=r.issue_id AND o.business_key=CAST(r.id AS CHAR) AND o.event_type=IF(r.consumption_id IS NULL,'ProductionReturnReversed','ProductionConsumptionReversed')
WHERE (r.status='APPROVED' AND o.message_id IS NULL) OR (r.status<>'APPROVED' AND o.message_id IS NOT NULL);
