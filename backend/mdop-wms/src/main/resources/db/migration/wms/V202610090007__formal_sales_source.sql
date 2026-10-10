ALTER TABLE wms_sales_order
 ADD source_system VARCHAR(24) NOT NULL DEFAULT 'ERP_SIMULATOR',
 ADD sales_arrangement_id BIGINT UNIQUE, ADD sales_order_line_id BIGINT,
 ADD sales_payload_hash CHAR(64),
 ADD CONSTRAINT ck_formal_sales_source CHECK (
  (source_system='ERP_SIMULATOR' AND sales_arrangement_id IS NULL AND sales_order_line_id IS NULL AND sales_payload_hash IS NULL)
  OR (source_system='MDOP_SALES' AND sales_arrangement_id IS NOT NULL AND sales_order_line_id IS NOT NULL AND sales_payload_hash IS NOT NULL));
