CREATE TABLE mdm_warehouse
(
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '仓库ID',
  code                VARCHAR(32)  NOT NULL COMMENT '仓库编码',
  name                VARCHAR(100) NOT NULL COMMENT '仓库名称',
  purpose             VARCHAR(32)  NOT NULL COMMENT '主要用途',
  form                VARCHAR(16)  NOT NULL COMMENT '仓库形态',
  management_category VARCHAR(32)  NOT NULL COMMENT '管理类别',
  status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '启用状态',
  remark              VARCHAR(500) NULL COMMENT '备注',
  version             BIGINT       NOT NULL DEFAULT 0 COMMENT '数据版本',
  created_by          VARCHAR(64)  NOT NULL COMMENT '创建人',
  created_at          DATETIME(6)  NOT NULL COMMENT '创建时间',
  updated_by          VARCHAR(64)  NOT NULL COMMENT '修改人',
  updated_at          DATETIME(6)  NOT NULL COMMENT '修改时间',
  deleted_by          VARCHAR(64)  NULL COMMENT '删除人',
  deleted_at          DATETIME(6)  NULL COMMENT '删除时间',

  CONSTRAINT pk_mdm_warehouse PRIMARY KEY (id),
  CONSTRAINT uk_mdm_warehouse_code UNIQUE (code),
  CONSTRAINT ck_mdm_warehouse_purpose
    CHECK (purpose IN ('RAW_MATERIAL', 'SEMI_FINISHED', 'FINISHED_GOODS', 'LINE_SIDE', 'MRO')),
  CONSTRAINT ck_mdm_warehouse_form
    CHECK (form IN ('PHYSICAL', 'LOGICAL')),
  CONSTRAINT ck_mdm_warehouse_management_category
    CHECK (management_category IN ('GENERAL', 'HAZARDOUS_CHEMICAL')),
  CONSTRAINT ck_mdm_warehouse_status
    CHECK (status IN ('ENABLED', 'DISABLED')),
  CONSTRAINT ck_mdm_warehouse_version
    CHECK (version >= 0),
  CONSTRAINT ck_mdm_warehouse_deleted_audit
    CHECK (
      (deleted_at IS NULL AND deleted_by IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
      )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '仓库主数据';
