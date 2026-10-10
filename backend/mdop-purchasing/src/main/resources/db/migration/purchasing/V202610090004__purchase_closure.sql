ALTER TABLE pur_document DROP CHECK ck_pur_status;
ALTER TABLE pur_document ADD CONSTRAINT ck_pur_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CONVERTED','CANCELLED','FULFILLING','CLOSED'));
CREATE TABLE pur_closure (
    document_id BIGINT PRIMARY KEY,
    outcome VARCHAR(24) NOT NULL,
    fact_hash CHAR(64) NOT NULL,
    snapshot JSON NOT NULL,
    reason VARCHAR(500) NOT NULL,
    closed_by VARCHAR(100) NOT NULL,
    closed_at DATETIME(6) NOT NULL,
    FOREIGN KEY(document_id) REFERENCES pur_document(id),
    CHECK(outcome IN ('QUALIFIED','WITH_RETURNS'))
);
