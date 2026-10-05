ALTER TABLE wms_outbox
    ADD attempts INT NOT NULL DEFAULT 0,
    ADD next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD lease_token CHAR(36) NULL,
    ADD lease_until DATETIME(6) NULL,
    ADD last_error VARCHAR(256) NULL,
    ADD published_at DATETIME(6) NULL,
    ADD INDEX ix_outbox_dispatch(status,next_attempt_at,lease_until);

CREATE TABLE integration_inbox (
    message_id CHAR(36) PRIMARY KEY,
    source_system VARCHAR(32) NOT NULL,
    external_notice_no VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    envelope JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(256) NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6) NULL,
    arrival_id BIGINT NULL,
    INDEX ix_inbox_dispatch(status,next_attempt_at,lease_until)
);
CREATE TABLE integration_arrival_identity (
    source_system VARCHAR(32) NOT NULL,
    external_notice_no VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    arrival_id BIGINT NULL,
    PRIMARY KEY(source_system,external_notice_no)
);
CREATE TABLE integration_delivery_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    direction VARCHAR(16) NOT NULL,
    message_id CHAR(36) NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    detail VARCHAR(256) NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_delivery_message(message_id,id)
);
CREATE TABLE integration_simulated_result (
    target_system VARCHAR(16) NOT NULL,
    message_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    envelope JSON NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(target_system,message_id),
    UNIQUE KEY uk_simulated_business(target_system,event_type,aggregate_id)
);
CREATE TABLE integration_rejected_message (
    fingerprint CHAR(64) PRIMARY KEY,
    reason VARCHAR(64) NOT NULL,
    occurrences INT NOT NULL DEFAULT 1,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
