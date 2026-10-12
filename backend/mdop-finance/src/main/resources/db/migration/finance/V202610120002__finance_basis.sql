CREATE TABLE fin_baseline (
 id BIGINT PRIMARY KEY, status VARCHAR(16) NOT NULL DEFAULT 'UNINITIALIZED',
 policy VARCHAR(32) NOT NULL DEFAULT 'DEMO-CNY-V1', organization VARCHAR(100),
 start_date DATE, end_date DATE, reason VARCHAR(500),
 version BIGINT NOT NULL DEFAULT 0, created_by VARCHAR(100), reviewed_by VARCHAR(100),
 created_at DATETIME(6), reviewed_at DATETIME(6),
 CHECK(id=1), CHECK(status IN ('UNINITIALIZED','PENDING','ACTIVE','REJECTED')),
 CHECK(policy='DEMO-CNY-V1'), CHECK(end_date>=start_date)
);
INSERT INTO fin_baseline(id) VALUES(1);
CREATE TABLE fin_basis (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, direction VARCHAR(16) NOT NULL,
 warehouse_id BIGINT NOT NULL, order_id BIGINT NOT NULL, order_number VARCHAR(40) NOT NULL,
 order_line_id BIGINT NOT NULL, fact_id BIGINT NOT NULL, fact_number VARCHAR(80) NOT NULL,
 party_id BIGINT NOT NULL, party_name VARCHAR(100) NOT NULL,
 material_code VARCHAR(32) NOT NULL, material_name VARCHAR(100) NOT NULL, unit VARCHAR(16) NOT NULL,
 quantity DECIMAL(18,6) NOT NULL, unit_price DECIMAL(18,6) NOT NULL,
 amount DECIMAL(18,2) NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'CNY',
 reference VARCHAR(100) NOT NULL, business_date DATE NOT NULL,
 reason VARCHAR(500) NOT NULL, source_snapshot JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', version BIGINT NOT NULL DEFAULT 0,
 active_source VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin UNIQUE,
 created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 reviewed_by VARCHAR(100), reviewed_at DATETIME(6),
 FOREIGN KEY(warehouse_id) REFERENCES mdm_warehouse(id),
 CHECK(direction IN ('PAYABLE','RECEIVABLE')), CHECK(quantity>0 AND unit_price>0 AND amount>0),
 CHECK(currency='CNY'), CHECK(status IN ('DRAFT','PENDING','CONFIRMED','REJECTED','CANCELLED')),
 CHECK((status IN ('REJECTED','CANCELLED') AND active_source IS NULL) OR (status IN ('DRAFT','PENDING','CONFIRMED') AND active_source IS NOT NULL)),
 INDEX idx_fin_basis(warehouse_id,direction,id), INDEX idx_fin_order(direction,order_id)
);
CREATE TABLE fin_open_item (
 basis_id BIGINT PRIMARY KEY, amount DECIMAL(18,2) NOT NULL, confirmed_by VARCHAR(100) NOT NULL,
 confirmed_at DATETIME(6) NOT NULL,
 FOREIGN KEY(basis_id) REFERENCES fin_basis(id), CHECK(amount>0)
);
CREATE TABLE fin_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, basis_id BIGINT NULL, action VARCHAR(24) NOT NULL,
 reason VARCHAR(500) NOT NULL, actor VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(basis_id) REFERENCES fin_basis(id)
);
CREATE TABLE fin_command (
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 request_hash CHAR(64) NOT NULL, result_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL
);
