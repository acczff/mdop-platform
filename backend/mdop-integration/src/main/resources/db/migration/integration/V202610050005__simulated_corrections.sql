CREATE TABLE integration_simulated_receipt_state (
    target_system VARCHAR(16) NOT NULL,
    receipt_id BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    PRIMARY KEY(target_system,receipt_id)
);
INSERT INTO integration_simulated_receipt_state(target_system,receipt_id,state)
SELECT target_system,aggregate_id,'RECEIVED' FROM integration_simulated_result
WHERE event_type IN ('PurchaseReceiptConfirmed','IncomingInspectionRequested')
GROUP BY target_system,aggregate_id;
