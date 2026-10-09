CREATE TABLE mfg_demand (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, warehouse_id BIGINT NOT NULL,
 source_type VARCHAR(16) NOT NULL, source_reference VARCHAR(100) NOT NULL,
 sales_line_id BIGINT UNIQUE, sales_order_id BIGINT,
 product_id BIGINT NOT NULL, product_code VARCHAR(32) NOT NULL, product_name VARCHAR(100) NOT NULL,
 unit VARCHAR(16) NOT NULL, unit_id BIGINT NOT NULL, quantity DECIMAL(18,6) NOT NULL,
 needed_date DATE NOT NULL, purpose VARCHAR(500) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN', version BIGINT NOT NULL DEFAULT 0,
 created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id), FOREIGN KEY(product_id) REFERENCES mdm_material(id),
 FOREIGN KEY(sales_line_id) REFERENCES sal_line(id), FOREIGN KEY(sales_order_id) REFERENCES sal_document(id),
 UNIQUE KEY uk_mfg_source(warehouse_id,source_type,source_reference),
 CHECK(quantity>0), CHECK(status IN ('OPEN','CANCELLED')), CHECK(source_type IN ('MANUAL','SALES')),
 CHECK((source_type='SALES' AND sales_line_id IS NOT NULL AND sales_order_id IS NOT NULL) OR (source_type='MANUAL' AND sales_line_id IS NULL AND sales_order_id IS NULL))
);
CREATE TABLE mfg_order (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(64) NOT NULL UNIQUE,
 demand_id BIGINT NOT NULL, active_demand_id BIGINT UNIQUE,
 site_id BIGINT NOT NULL, site_code VARCHAR(32) NOT NULL, site_name VARCHAR(100) NOT NULL,
 warehouse_name VARCHAR(100) NOT NULL, bom_id BIGINT NOT NULL, bom_snapshot JSON NOT NULL,
 planned_date DATE NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', version BIGINT NOT NULL DEFAULT 0,
 created_by VARCHAR(100) NOT NULL, last_edited_by VARCHAR(100) NOT NULL, submitted_by VARCHAR(100),
 approved_by VARCHAR(100), approved_at DATETIME(6), created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(demand_id) REFERENCES mfg_demand(id), FOREIGN KEY(active_demand_id) REFERENCES mfg_demand(id),
 FOREIGN KEY(site_id) REFERENCES mdm_production_site(id), FOREIGN KEY(bom_id) REFERENCES mfg_bom(id),
 CHECK(status IN ('DRAFT','SUBMITTED','REJECTED','APPROVED','CANCELLED')),
 CHECK((status='CANCELLED' AND active_demand_id IS NULL) OR (status<>'CANCELLED' AND active_demand_id=demand_id))
);
CREATE TABLE mfg_order_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, demand_id BIGINT NOT NULL, order_id BIGINT,
 action VARCHAR(24) NOT NULL, reason VARCHAR(500) NOT NULL, before_state JSON, after_state JSON NOT NULL,
 created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(demand_id) REFERENCES mfg_demand(id), FOREIGN KEY(order_id) REFERENCES mfg_order(id)
);
CREATE TABLE mfg_order_command (
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, request_hash CHAR(64) NOT NULL,
 demand_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(demand_id) REFERENCES mfg_demand(id)
);
