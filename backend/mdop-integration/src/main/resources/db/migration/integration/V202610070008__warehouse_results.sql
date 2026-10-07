CREATE TABLE integration_warehouse_result (
 aggregate_type VARCHAR(32) NOT NULL,aggregate_id BIGINT NOT NULL,event_type VARCHAR(64) NOT NULL,target_system VARCHAR(16) NOT NULL,payload_hash CHAR(64) NOT NULL,received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(aggregate_type,aggregate_id,event_type,target_system)
);
CREATE TABLE integration_warehouse_message(message_id CHAR(36) PRIMARY KEY,payload_hash CHAR(64) NOT NULL,received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6));
