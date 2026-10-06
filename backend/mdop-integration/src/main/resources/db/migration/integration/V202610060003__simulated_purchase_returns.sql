ALTER TABLE integration_simulated_result ADD business_key VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE integration_simulated_result DROP INDEX uk_simulated_business;
ALTER TABLE integration_simulated_result ADD UNIQUE KEY uk_simulated_business(target_system,event_type,aggregate_id,business_key);
CREATE TABLE integration_simulated_purchase_return (
    return_id BIGINT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    quantity DECIMAL(18,6) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RETURNED',
    payload_hash CHAR(64) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CHECK (quantity>0 AND state='RETURNED')
);
