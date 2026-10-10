-- Existing documents already authorize their material identity, including open documents.
UPDATE mdm_material m SET identity_locked=TRUE WHERE
 EXISTS(SELECT 1 FROM wms_arrival_notice_item i WHERE i.material_id=m.id) OR
 EXISTS(SELECT 1 FROM wms_material_issue i WHERE i.material_id=m.id) OR
 EXISTS(SELECT 1 FROM wms_finished_receipt i WHERE i.material_id=m.id) OR
 EXISTS(SELECT 1 FROM wms_sales_order i WHERE i.material_id=m.id) OR
 EXISTS(SELECT 1 FROM wms_inventory_balance i WHERE i.material_id=m.id);
ALTER TABLE wms_arrival_notice ADD supplier_code VARCHAR(32), ADD supplier_name VARCHAR(100);
UPDATE wms_arrival_notice a JOIN mdm_supplier s ON s.id=a.supplier_id
 SET a.supplier_code=s.code,a.supplier_name=s.name;
ALTER TABLE wms_arrival_notice MODIFY supplier_code VARCHAR(32) NOT NULL, MODIFY supplier_name VARCHAR(100) NOT NULL;
ALTER TABLE wms_material_issue ADD material_code VARCHAR(32), ADD material_name VARCHAR(100), ADD unit VARCHAR(16);
UPDATE wms_material_issue d JOIN mdm_material m ON m.id=d.material_id
 SET d.material_code=m.code,d.material_name=m.name,d.unit=m.unit;
ALTER TABLE wms_material_issue MODIFY material_code VARCHAR(32) NOT NULL, MODIFY material_name VARCHAR(100) NOT NULL, MODIFY unit VARCHAR(16) NOT NULL;
ALTER TABLE wms_finished_receipt ADD material_code VARCHAR(32), ADD material_name VARCHAR(100), ADD unit VARCHAR(16);
UPDATE wms_finished_receipt d JOIN mdm_material m ON m.id=d.material_id
 SET d.material_code=m.code,d.material_name=m.name,d.unit=m.unit;
ALTER TABLE wms_finished_receipt MODIFY material_code VARCHAR(32) NOT NULL, MODIFY material_name VARCHAR(100) NOT NULL, MODIFY unit VARCHAR(16) NOT NULL;
ALTER TABLE wms_sales_order ADD material_code VARCHAR(32), ADD material_name VARCHAR(100), ADD unit VARCHAR(16);
UPDATE wms_sales_order d JOIN mdm_material m ON m.id=d.material_id
 SET d.material_code=m.code,d.material_name=m.name,d.unit=m.unit;
ALTER TABLE wms_sales_order MODIFY material_code VARCHAR(32) NOT NULL, MODIFY material_name VARCHAR(100) NOT NULL, MODIFY unit VARCHAR(16) NOT NULL;
