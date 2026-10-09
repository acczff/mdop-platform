-- Serialize BOM graph changes before locking individual materials or versions.
CREATE TABLE mfg_bom_guard (id INT NOT NULL PRIMARY KEY);
INSERT INTO mfg_bom_guard VALUES (1);
CREATE TABLE mfg_bom (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 product_id BIGINT NOT NULL,
 product_code VARCHAR(32) NOT NULL,
 product_name VARCHAR(100) NOT NULL,
 product_unit VARCHAR(32) NOT NULL,
 product_unit_id BIGINT NOT NULL,
 version_label VARCHAR(32) NOT NULL,
 base_quantity DECIMAL(18,6) NOT NULL,
 description VARCHAR(500) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 version BIGINT NOT NULL DEFAULT 0,
 copied_from_id BIGINT NULL,
 created_by VARCHAR(100) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 published_by VARCHAR(100) NULL,
 published_at DATETIME(6) NULL,
 disabled_by VARCHAR(100) NULL,
 disabled_at DATETIME(6) NULL,
 UNIQUE KEY uk_bom_product_version(product_id,version_label),
 FOREIGN KEY(product_id) REFERENCES mdm_material(id),
 FOREIGN KEY(product_unit_id) REFERENCES mdm_unit(id),
 FOREIGN KEY(copied_from_id) REFERENCES mfg_bom(id),
 CHECK(base_quantity>0),
 CHECK(status IN ('DRAFT','PUBLISHED','DISABLED'))
);
CREATE TABLE mfg_bom_component (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 bom_id BIGINT NOT NULL,
 material_id BIGINT NOT NULL,
 material_code VARCHAR(32) NOT NULL,
 material_name VARCHAR(100) NOT NULL,
 unit VARCHAR(32) NOT NULL,
 unit_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 UNIQUE KEY uk_bom_component(bom_id,material_id),
 FOREIGN KEY(bom_id) REFERENCES mfg_bom(id),
 FOREIGN KEY(material_id) REFERENCES mdm_material(id),
 FOREIGN KEY(unit_id) REFERENCES mdm_unit(id),
 CHECK(quantity>0)
);
CREATE TABLE mfg_bom_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 bom_id BIGINT NOT NULL,
 action VARCHAR(24) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 before_state JSON NULL,
 after_state JSON NOT NULL,
 created_by VARCHAR(100) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(bom_id) REFERENCES mfg_bom(id)
);
CREATE TABLE mfg_bom_command (
 request_key VARCHAR(64) NOT NULL PRIMARY KEY,
 request_hash CHAR(64) NOT NULL,
 bom_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(bom_id) REFERENCES mfg_bom(id)
);
