CREATE TABLE integration_mes_result (
 event_type VARCHAR(64) NOT NULL,
 issue_id BIGINT NOT NULL,
 business_key VARCHAR(64) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 work_order_no VARCHAR(64) NOT NULL,
 received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(event_type,issue_id,business_key),
 CHECK(quantity>0)
);
CREATE TABLE integration_mes_message (
 message_id CHAR(36) PRIMARY KEY,
 payload_hash CHAR(64) NOT NULL,
 received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
