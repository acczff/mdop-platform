CREATE TABLE pur_document (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_no VARCHAR(40) NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    request_id BIGINT NULL,
    active_order_id BIGINT NULL,
    warehouse_id BIGINT NOT NULL,
    warehouse_name VARCHAR(100) NOT NULL,
    supplier_id BIGINT NULL,
    supplier_name VARCHAR(100) NULL,
    purpose VARCHAR(500) NOT NULL,
    needed_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(100) NOT NULL,
    last_edited_by VARCHAR(100) NOT NULL,
    submitted_by VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_pur_warehouse FOREIGN KEY (warehouse_id) REFERENCES mdm_warehouse(id),
    CONSTRAINT fk_pur_supplier FOREIGN KEY (supplier_id) REFERENCES mdm_supplier(id),
    CONSTRAINT fk_pur_request FOREIGN KEY (request_id) REFERENCES pur_document(id),
    CONSTRAINT fk_pur_active_order FOREIGN KEY (active_order_id) REFERENCES pur_document(id),
    CONSTRAINT ck_pur_kind CHECK (kind IN ('REQUEST','ORDER')),
    CONSTRAINT ck_pur_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CONVERTED','CANCELLED')),
    CONSTRAINT ck_pur_source CHECK ((kind='REQUEST' AND request_id IS NULL AND supplier_id IS NULL AND source_type='MANUAL') OR (kind='ORDER' AND request_id IS NOT NULL AND supplier_id IS NOT NULL AND source_type='PURCHASE_REQUEST')),
    INDEX idx_pur_list (warehouse_id,kind,id)
);

CREATE TABLE pur_line (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    source_line_id BIGINT NULL,
    material_id BIGINT NOT NULL,
    material_code VARCHAR(32) NOT NULL,
    material_name VARCHAR(100) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    quantity DECIMAL(18,6) NOT NULL,
    CONSTRAINT fk_pur_line_document FOREIGN KEY (document_id) REFERENCES pur_document(id),
    CONSTRAINT fk_pur_line_source FOREIGN KEY (source_line_id) REFERENCES pur_line(id),
    CONSTRAINT fk_pur_line_material FOREIGN KEY (material_id) REFERENCES mdm_material(id),
    CONSTRAINT ck_pur_line_quantity CHECK (quantity>0),
    UNIQUE KEY uk_pur_document_material(document_id,material_id)
);

CREATE TABLE pur_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    before_state JSON NULL,
    after_state JSON NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_pur_audit_document FOREIGN KEY (document_id) REFERENCES pur_document(id)
);

CREATE TABLE pur_command (
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    document_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_pur_command_document FOREIGN KEY (document_id) REFERENCES pur_document(id)
);
