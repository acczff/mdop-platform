-- Read-only reconciliation for the current receiving/quality/return stage.
-- Every mismatch_count must be zero. Future reservation flows must extend the availability rule.
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
WHERE b.available_qty<>CASE WHEN b.quality_status='QUALIFIED' AND l.area_type='STORAGE' THEN b.on_hand_qty ELSE 0 END
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
WHERE p.quantity>q.rejected_qty;
