CREATE TABLE mfg_completion (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL, report_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 quantity DECIMAL(18,6) NOT NULL, batch_no VARCHAR(64) NOT NULL,
 date_code VARCHAR(32) NOT NULL, production_date DATE NOT NULL, expiry_date DATE,
 reason VARCHAR(500) NOT NULL, source_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
 status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED', version BIGINT NOT NULL DEFAULT 0,
 created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 reviewed_by VARCHAR(100), reviewed_at DATETIME(6), review_reason VARCHAR(500),
 FOREIGN KEY(order_id) REFERENCES mfg_order(id), INDEX ix_completion_order(order_id,id),
 CHECK(quantity>0), CHECK(source_type='MANUAL'),
 CHECK(status IN('SUBMITTED','APPROVED','REJECTED','CANCELLED')),
 CHECK(expiry_date IS NULL OR expiry_date>=production_date),
 CHECK(status NOT IN('APPROVED','REJECTED') OR (reviewed_by IS NOT NULL AND reviewed_by<>created_by))
);
CREATE TABLE mfg_completion_command (
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 request_hash CHAR(64) NOT NULL, report_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL, FOREIGN KEY(report_id) REFERENCES mfg_completion(id)
);
ALTER TABLE wms_finished_receipt ADD COLUMN production_order_id BIGINT,
 ADD COLUMN completion_report_id BIGINT UNIQUE,
 ADD CONSTRAINT fk_finished_order FOREIGN KEY(production_order_id) REFERENCES mfg_order(id),
 ADD CONSTRAINT fk_finished_report FOREIGN KEY(completion_report_id) REFERENCES mfg_completion(id),
 ADD CONSTRAINT ck_finished_source CHECK((production_order_id IS NULL AND completion_report_id IS NULL) OR (production_order_id IS NOT NULL AND completion_report_id IS NOT NULL));
