ALTER TABLE pur_document
    ADD COLUMN original_order_id BIGINT NULL,
    ADD CONSTRAINT fk_pur_original_order FOREIGN KEY (original_order_id) REFERENCES pur_document(id),
    ADD INDEX idx_pur_original_order (original_order_id);
