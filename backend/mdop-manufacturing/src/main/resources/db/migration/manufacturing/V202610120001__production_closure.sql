ALTER TABLE mfg_order DROP CHECK mfg_order_chk_1,
 ADD CONSTRAINT ck_mfg_order_status CHECK(status IN ('DRAFT','SUBMITTED','REJECTED','APPROVED','CANCELLED','COMPLETED'));

CREATE TABLE mfg_order_closure (
 order_id BIGINT PRIMARY KEY, fact_hash CHAR(64) NOT NULL, snapshot JSON NOT NULL,
 reason VARCHAR(500) NOT NULL, variance_reason VARCHAR(500) NOT NULL,
 closed_by VARCHAR(100) NOT NULL, closed_at DATETIME(6) NOT NULL,
 FOREIGN KEY(order_id) REFERENCES mfg_order(id)
);
