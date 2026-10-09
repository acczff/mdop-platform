CREATE TABLE sal_document (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, document_no VARCHAR(40) NOT NULL UNIQUE,
 warehouse_id BIGINT NOT NULL, warehouse_name VARCHAR(100) NOT NULL,
 customer_id BIGINT NOT NULL, customer_code VARCHAR(32) NOT NULL, customer_name VARCHAR(100) NOT NULL,
 customer_reference VARCHAR(128), purpose VARCHAR(500) NOT NULL, needed_date DATE NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', version BIGINT NOT NULL DEFAULT 0,
 created_by VARCHAR(100) NOT NULL, last_edited_by VARCHAR(100) NOT NULL, submitted_by VARCHAR(100),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id), FOREIGN KEY(customer_id) REFERENCES mdm_customer(id),
 CHECK(status IN ('DRAFT','SUBMITTED','REJECTED','APPROVED','FULFILLING','CLOSED','CANCELLED')),
 INDEX idx_sal_list(warehouse_id,id)
);
CREATE TABLE sal_line (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, document_id BIGINT NOT NULL, material_id BIGINT NOT NULL,
 material_code VARCHAR(32) NOT NULL, material_name VARCHAR(100) NOT NULL, unit VARCHAR(16) NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 FOREIGN KEY(document_id) REFERENCES sal_document(id), FOREIGN KEY(material_id) REFERENCES mdm_material(id),
 CHECK(quantity>0), UNIQUE KEY uk_sal_material(document_id,material_id)
);
CREATE TABLE sal_arrangement (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, order_line_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL, expected_date DATE NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', wms_sales_id BIGINT UNIQUE, last_error VARCHAR(350),
 created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(order_id) REFERENCES sal_document(id), FOREIGN KEY(order_line_id) REFERENCES sal_line(id),
 CHECK(quantity>0), CHECK(status IN ('PENDING','DELIVERED','WITHDRAWN')),
 CHECK(status<>'DELIVERED' OR wms_sales_id IS NOT NULL), INDEX idx_sal_arrangement(order_id,id)
);
CREATE TABLE sal_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, document_id BIGINT NOT NULL, action VARCHAR(24) NOT NULL,
 reason VARCHAR(500) NOT NULL, before_state JSON, after_state JSON NOT NULL,
 created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(document_id) REFERENCES sal_document(id)
);
CREATE TABLE sal_command (
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, request_hash CHAR(64) NOT NULL,
 document_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(document_id) REFERENCES sal_document(id)
);
CREATE TABLE sal_closure (
 document_id BIGINT PRIMARY KEY, fact_hash CHAR(64) NOT NULL, snapshot JSON NOT NULL,
 reason VARCHAR(500) NOT NULL, closed_by VARCHAR(100) NOT NULL, closed_at DATETIME(6) NOT NULL,
 FOREIGN KEY(document_id) REFERENCES sal_document(id)
);
