ALTER TABLE pur_document DROP CHECK ck_pur_status;
ALTER TABLE pur_document ADD CONSTRAINT ck_pur_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CONVERTED','CANCELLED','FULFILLING'));
CREATE TABLE pur_arrangement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    expected_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    wms_arrival_id BIGINT NULL,
    last_error VARCHAR(500) NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_pur_arrangement_order FOREIGN KEY (order_id) REFERENCES pur_document(id),
    CONSTRAINT ck_pur_arrangement_status CHECK (status IN ('PENDING','DELIVERED','WITHDRAWN'))
);
CREATE TABLE pur_arrangement_line (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    arrangement_id BIGINT NOT NULL,
    order_line_id BIGINT NOT NULL,
    quantity DECIMAL(18,6) NOT NULL,
    wms_arrival_item_id BIGINT NULL,
    CONSTRAINT fk_pur_arrangement_line_parent FOREIGN KEY (arrangement_id) REFERENCES pur_arrangement(id),
    CONSTRAINT fk_pur_arrangement_line_order FOREIGN KEY (order_line_id) REFERENCES pur_line(id),
    CONSTRAINT ck_pur_arrangement_quantity CHECK (quantity>0),
    UNIQUE KEY uk_pur_arrangement_order_line (arrangement_id,order_line_id)
);
