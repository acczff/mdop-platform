CREATE TABLE mdm_supplier (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL
);
CREATE TABLE mdm_material (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    tracking_mode VARCHAR(16) NOT NULL,
    require_date_code BOOLEAN NOT NULL,
    require_expiry BOOLEAN NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_material_tracking CHECK (tracking_mode IN ('QUANTITY','BATCH'))
);
CREATE TABLE mdm_location (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    area_type VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_location_warehouse_code (warehouse_id, code),
    CONSTRAINT fk_location_warehouse FOREIGN KEY (warehouse_id) REFERENCES mdm_warehouse(id),
    CONSTRAINT ck_location_area CHECK (area_type IN ('RECEIVING','INSPECTION','STORAGE'))
);
