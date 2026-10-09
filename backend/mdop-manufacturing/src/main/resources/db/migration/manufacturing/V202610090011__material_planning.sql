CREATE TABLE mfg_material_plan (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL,
 version BIGINT NOT NULL, fact_hash CHAR(64) NOT NULL, snapshot JSON NOT NULL,
 created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(order_id) REFERENCES mfg_order(id), FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id),
 UNIQUE KEY uk_mfg_plan_version(order_id,version)
);
CREATE TABLE mfg_material_plan_line (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id BIGINT NOT NULL, material_id BIGINT NOT NULL,
 suggested_quantity DECIMAL(18,6) NOT NULL, purchase_request_id BIGINT UNIQUE,
 FOREIGN KEY(plan_id) REFERENCES mfg_material_plan(id), FOREIGN KEY(material_id) REFERENCES mdm_material(id),
 FOREIGN KEY(purchase_request_id) REFERENCES pur_document(id),
 UNIQUE KEY uk_mfg_plan_material(plan_id,material_id), CHECK(suggested_quantity>=0)
);
CREATE TABLE mfg_material_command (
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, request_hash CHAR(64) NOT NULL,
 order_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL, FOREIGN KEY(order_id) REFERENCES mfg_order(id)
);
ALTER TABLE pur_document ADD COLUMN production_order_id BIGINT,
 ADD COLUMN production_order_no VARCHAR(64), ADD COLUMN production_plan_line_id BIGINT UNIQUE,
 ADD CONSTRAINT fk_pur_production_order FOREIGN KEY(production_order_id) REFERENCES mfg_order(id),
 ADD CONSTRAINT fk_pur_production_line FOREIGN KEY(production_plan_line_id) REFERENCES mfg_material_plan_line(id),
 DROP CHECK ck_pur_source,
 ADD CONSTRAINT ck_pur_source CHECK (
 (kind='REQUEST' AND request_id IS NULL AND supplier_id IS NULL AND ((source_type='MANUAL' AND production_order_id IS NULL AND production_plan_line_id IS NULL) OR (source_type='MANUFACTURING' AND production_order_id IS NOT NULL AND production_order_no IS NOT NULL AND production_plan_line_id IS NOT NULL)))
 OR (kind='ORDER' AND request_id IS NOT NULL AND supplier_id IS NOT NULL AND source_type='PURCHASE_REQUEST'));
