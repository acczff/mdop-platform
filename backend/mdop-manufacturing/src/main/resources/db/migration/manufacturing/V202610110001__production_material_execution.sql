ALTER TABLE mfg_order ADD COLUMN material_target_warehouse_id BIGINT,
 ADD CONSTRAINT fk_mfg_order_line_warehouse FOREIGN KEY(material_target_warehouse_id) REFERENCES mdm_warehouse(id);
ALTER TABLE wms_material_issue ADD COLUMN production_order_id BIGINT,
 ADD COLUMN production_plan_line_id BIGINT,
 ADD CONSTRAINT fk_issue_production_order FOREIGN KEY(production_order_id) REFERENCES mfg_order(id),
 ADD CONSTRAINT fk_issue_production_line FOREIGN KEY(production_plan_line_id) REFERENCES mfg_material_plan_line(id),
 ADD CONSTRAINT ck_issue_production_source CHECK((production_order_id IS NULL AND production_plan_line_id IS NULL) OR (production_order_id IS NOT NULL AND production_plan_line_id IS NOT NULL)),
 ADD INDEX ix_issue_production_material(production_order_id, material_id);
ALTER TABLE wms_production_consumption ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'SIMULATED',
 ADD COLUMN reason VARCHAR(500),
 ADD CONSTRAINT ck_consumption_source CHECK(source_type IN ('SIMULATED','MANUAL') AND (source_type='SIMULATED' OR reason IS NOT NULL));
ALTER TABLE wms_production_return ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'SIMULATED',
 ADD CONSTRAINT ck_production_return_source CHECK(source_type IN ('SIMULATED','MANUAL'));
