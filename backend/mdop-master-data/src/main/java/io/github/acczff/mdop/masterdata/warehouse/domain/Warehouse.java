package io.github.acczff.mdop.masterdata.warehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "mdm_warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uk_mdm_warehouse_code", columnNames = "code"))
public class Warehouse {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9-]{1,31}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, updatable = false)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private WarehousePurpose purpose;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private WarehouseForm form;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "management_category", nullable = false, length = 32)
    private WarehouseManagementCategory managementCategory;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private WarehouseStatus status;

    @Column(length = 500)
    private String remark;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_by", nullable = false, length = 64, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_by", length = 64)
    private String deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Warehouse() {}

    public static Warehouse create(
            String code,
            String name,
            WarehousePurpose purpose,
            WarehouseForm form,
            WarehouseManagementCategory managementCategory,
            String remark,
            String actor,
            Instant occurredAt) {
        String normalizedActor = normalizeActor(actor);
        Instant normalizedTime = Objects.requireNonNull(occurredAt, "发生时间不能为空");

        Warehouse warehouse = new Warehouse();
        warehouse.code = normalizeCode(code);
        warehouse.name = normalizeName(name);
        warehouse.purpose = Objects.requireNonNull(purpose, "主要用途不能为空");
        warehouse.form = Objects.requireNonNull(form, "仓库形态不能为空");
        warehouse.managementCategory = Objects.requireNonNull(managementCategory, "管理类别不能为空");
        warehouse.status = WarehouseStatus.ENABLED;
        warehouse.remark = normalizeRemark(remark);
        warehouse.version = 0L;
        warehouse.createdBy = normalizedActor;
        warehouse.createdAt = normalizedTime;
        warehouse.updatedBy = normalizedActor;
        warehouse.updatedAt = normalizedTime;
        return warehouse;
    }

    public void updateBasicInformation(
            String name, String remark, String actor, Instant occurredAt) {
        String normalizedName = normalizeName(name);
        String normalizedRemark = normalizeRemark(remark);
        String normalizedActor = normalizeActor(actor);
        Instant normalizedTime = Objects.requireNonNull(occurredAt, "发生时间不能为空");

        this.name = normalizedName;
        this.remark = normalizedRemark;
        touch(normalizedActor, normalizedTime);
    }

    public void updateStructure(
            WarehousePurpose purpose,
            WarehouseForm form,
            WarehouseManagementCategory managementCategory,
            String actor,
            Instant occurredAt) {
        if (status != WarehouseStatus.DISABLED) {
            throw new WarehouseException(
                    WarehouseErrorCode.WAREHOUSE_STRUCTURE_CHANGE_REQUIRES_DISABLED,
                    "仓库必须先停用才能修改结构属性");
        }

        WarehousePurpose newPurpose = Objects.requireNonNull(purpose, "主要用途不能为空");
        WarehouseForm newForm = Objects.requireNonNull(form, "仓库形态不能为空");
        WarehouseManagementCategory newManagementCategory =
                Objects.requireNonNull(managementCategory, "管理类别不能为空");
        String normalizedActor = normalizeActor(actor);
        Instant normalizedTime = Objects.requireNonNull(occurredAt, "发生时间不能为空");

        this.purpose = newPurpose;
        this.form = newForm;
        this.managementCategory = newManagementCategory;
        touch(normalizedActor, normalizedTime);
    }

    public void enable(String actor, Instant occurredAt) {
        changeStatus(WarehouseStatus.ENABLED, actor, occurredAt);
    }

    public void disable(String actor, Instant occurredAt) {
        changeStatus(WarehouseStatus.DISABLED, actor, occurredAt);
    }

    private void changeStatus(WarehouseStatus target, String actor, Instant occurredAt) {
        if (status == target) {
            return;
        }

        String normalizedActor = normalizeActor(actor);
        Instant normalizedTime = Objects.requireNonNull(occurredAt, "发生时间不能为空");

        this.status = target;
        touch(normalizedActor, normalizedTime);
    }

    private void touch(String actor, Instant occurredAt) {
        this.updatedBy = actor;
        this.updatedAt = occurredAt;
    }

    private static String normalizeCode(String value) {
        String normalized = requireText(value, "仓库编码").toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("仓库编码格式不正确");
        }
        return normalized;
    }

    private static String normalizeName(String value) {
        String normalized = requireText(value, "仓库名称");
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("仓库名称不能超过100个字符");
        }
        return normalized;
    }

    private static String normalizeRemark(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("备注不能超过500个字符");
        }
        return normalized;
    }

    private static String normalizeActor(String value) {
        String normalized = requireText(value, "操作者");
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("操作者不能超过64个字符");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value.trim();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public WarehousePurpose getPurpose() {
        return purpose;
    }

    public WarehouseForm getForm() {
        return form;
    }

    public WarehouseManagementCategory getManagementCategory() {
        return managementCategory;
    }

    public WarehouseStatus getStatus() {
        return status;
    }

    public String getRemark() {
        return remark;
    }

    public long getVersion() {
        return version;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getDeletedBy() {
        return deletedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
