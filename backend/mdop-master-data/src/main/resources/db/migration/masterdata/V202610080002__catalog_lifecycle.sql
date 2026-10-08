CREATE TABLE mdm_unit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(80) NOT NULL UNIQUE,
 name VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL UNIQUE,
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_unit_name CHECK(CHAR_LENGTH(TRIM(name)) > 0 AND name = TRIM(name)),
 CONSTRAINT ck_unit_status CHECK(status IN ('ENABLED','DISABLED'))
);
-- Preserve exact legacy labels: no case folding, alias guessing or unit conversion.
INSERT INTO mdm_unit(code,name)
 SELECT DISTINCT CONCAT('LEGACY-',SHA2(unit,256)),unit FROM mdm_material;
INSERT INTO mdm_unit(code,name)
 SELECT 'UNIT-PIECE','件' WHERE NOT EXISTS(SELECT 1 FROM mdm_unit WHERE name='件');
ALTER TABLE mdm_material
 ADD unit_id BIGINT,
 ADD status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
 ADD version BIGINT NOT NULL DEFAULT 0,
 ADD identity_locked BOOLEAN NOT NULL DEFAULT FALSE,
 ADD CONSTRAINT ck_material_status CHECK(status IN ('ENABLED','DISABLED'));
UPDATE mdm_material m JOIN mdm_unit u ON CAST(m.unit AS BINARY)=CAST(u.name AS BINARY) SET m.unit_id=u.id;
ALTER TABLE mdm_material MODIFY unit_id BIGINT NOT NULL,
 ADD CONSTRAINT fk_material_unit FOREIGN KEY(unit_id) REFERENCES mdm_unit(id);
ALTER TABLE mdm_supplier ADD status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
 ADD version BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT ck_supplier_status CHECK(status IN ('ENABLED','DISABLED'));
CREATE TABLE mdm_customer (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(32) NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
 version BIGINT NOT NULL DEFAULT 0,
 created_by VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_customer_status CHECK(status IN ('ENABLED','DISABLED'))
);
CREATE TABLE mdm_organization (
 id BIGINT PRIMARY KEY,
 code VARCHAR(32) NOT NULL,
 name VARCHAR(100) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_single_organization CHECK(id=1)
);
CREATE TABLE mdm_catalog_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 entity_type VARCHAR(20) NOT NULL,
 entity_id BIGINT NOT NULL,
 action_type VARCHAR(20) NOT NULL,
 before_state JSON,
 after_state JSON NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 INDEX ix_catalog_audit(entity_type,entity_id,id)
);
