CREATE TABLE wms_arrival_notice (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(32) NOT NULL,
    external_notice_no VARCHAR(64) NOT NULL,
    purchase_order_no VARCHAR(64) NOT NULL,
    supplier_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_RECEIPT',
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_arrival_source (source_system, external_notice_no),
    FOREIGN KEY (supplier_id) REFERENCES mdm_supplier(id),
    FOREIGN KEY (warehouse_id) REFERENCES mdm_warehouse(id),
    CHECK (status IN ('PENDING_RECEIPT','PARTIALLY_RECEIVED','RECEIVED'))
);
CREATE TABLE wms_arrival_notice_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    arrival_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    material_code VARCHAR(32) NOT NULL,
    material_name VARCHAR(100) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    notice_qty DECIMAL(18,6) NOT NULL,
    received_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
    FOREIGN KEY (arrival_id) REFERENCES wms_arrival_notice(id),
    FOREIGN KEY (material_id) REFERENCES mdm_material(id),
    UNIQUE KEY uk_arrival_material (arrival_id,material_id),
    CHECK (notice_qty > 0 AND received_qty >= 0 AND received_qty <= notice_qty)
);
CREATE TABLE wms_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_no VARCHAR(40) NOT NULL UNIQUE,
    arrival_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    creation_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    creation_hash CHAR(64) NOT NULL,
    submit_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    submitted_by VARCHAR(64),
    submitted_at DATETIME(6),
    FOREIGN KEY (arrival_id) REFERENCES wms_arrival_notice(id),
    CHECK (status IN ('DRAFT','SUBMITTED'))
);
CREATE TABLE wms_receipt_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    arrival_item_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    quantity DECIMAL(18,6) NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT '',
    date_code VARCHAR(32) NOT NULL DEFAULT '',
    production_date DATE,
    expiry_date DATE,
    FOREIGN KEY (receipt_id) REFERENCES wms_receipt(id),
    FOREIGN KEY (arrival_item_id) REFERENCES wms_arrival_notice_item(id),
    FOREIGN KEY (material_id) REFERENCES mdm_material(id),
    FOREIGN KEY (location_id) REFERENCES mdm_location(id),
    CHECK (quantity > 0)
);
CREATE TABLE wms_inventory_balance (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    stock_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    warehouse_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    date_code VARCHAR(32) NOT NULL,
    production_date DATE,
    expiry_date DATE,
    quality_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_INSPECTION',
    owner_type VARCHAR(16) NOT NULL DEFAULT 'ENTERPRISE',
    owner_id BIGINT NOT NULL DEFAULT 0,
    on_hand_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
    available_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
    FOREIGN KEY (warehouse_id) REFERENCES mdm_warehouse(id),
    FOREIGN KEY (location_id) REFERENCES mdm_location(id),
    FOREIGN KEY (material_id) REFERENCES mdm_material(id),
    FOREIGN KEY (supplier_id) REFERENCES mdm_supplier(id),
    CHECK (quality_status = 'PENDING_INSPECTION' AND on_hand_qty >= 0 AND available_qty = 0),
    CHECK (owner_type = 'ENTERPRISE' AND owner_id = 0)
);
CREATE TABLE wms_inventory_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    receipt_item_id BIGINT NOT NULL UNIQUE,
    balance_id BIGINT NOT NULL,
    before_qty DECIMAL(18,6) NOT NULL,
    change_qty DECIMAL(18,6) NOT NULL,
    after_qty DECIMAL(18,6) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (receipt_id) REFERENCES wms_receipt(id),
    FOREIGN KEY (receipt_item_id) REFERENCES wms_receipt_item(id),
    FOREIGN KEY (balance_id) REFERENCES wms_inventory_balance(id),
    CHECK (before_qty >= 0 AND change_qty > 0 AND after_qty = before_qty + change_qty)
);
CREATE TABLE wms_receiving_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    FOREIGN KEY (receipt_id) REFERENCES wms_receipt(id)
);
CREATE TABLE wms_outbox (
    message_id CHAR(36) NOT NULL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    source_system VARCHAR(16) NOT NULL DEFAULT 'WMS',
    aggregate_type VARCHAR(32) NOT NULL DEFAULT 'Receipt',
    aggregate_id BIGINT NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    occurred_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_outbox_receipt_event (aggregate_id,event_type),
    KEY ix_outbox_pending (status,occurred_at),
    FOREIGN KEY (aggregate_id) REFERENCES wms_receipt(id)
);
