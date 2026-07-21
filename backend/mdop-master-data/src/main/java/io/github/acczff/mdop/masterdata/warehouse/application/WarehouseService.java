package io.github.acczff.mdop.masterdata.warehouse.application;

import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.warehouse.domain.Warehouse;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseErrorCode;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseException;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseForm;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseManagementCategory;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehousePurpose;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseStatus;
import io.github.acczff.mdop.masterdata.warehouse.infrastructure.WarehouseRepository;
import io.github.acczff.mdop.masterdata.warehouse.infrastructure.WarehouseSpecifications;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WarehouseService {

    private final WarehouseRepository repository;
    private final CurrentActorProvider currentActorProvider;
    private final Clock clock;

    public WarehouseService(
            WarehouseRepository repository,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        this.repository = repository;
        this.currentActorProvider = currentActorProvider;
        this.clock = clock;
    }

    public Warehouse create(
            String code,
            String name,
            WarehousePurpose purpose,
            WarehouseForm form,
            WarehouseManagementCategory managementCategory,
            String remark) {
        Warehouse warehouse =
                Warehouse.create(
                        code,
                        name,
                        purpose,
                        form,
                        managementCategory,
                        remark,
                        currentActorProvider.currentActor(),
                        clock.instant());

        if (repository.existsByCode(warehouse.getCode())) {
            throw new WarehouseException(
                    WarehouseErrorCode.WAREHOUSE_CODE_DUPLICATE,
                    "仓库编码 " + warehouse.getCode() + " 已存在");
        }

        return repository.save(warehouse);
    }

    @Transactional(readOnly = true)
    public Warehouse get(Long id) {
        return getRequired(id);
    }

    @Transactional(readOnly = true)
    public Page<Warehouse> search(WarehouseSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "查询条件不能为空");

        PageRequest pageable =
                PageRequest.of(
                        criteria.page() - 1, criteria.size(), Sort.by(Sort.Order.asc("code")));

        return repository.findAll(WarehouseSpecifications.matching(criteria), pageable);
    }

    public Warehouse update(
            Long id,
            String name,
            WarehousePurpose purpose,
            WarehouseForm form,
            WarehouseManagementCategory managementCategory,
            String remark,
            long expectedVersion) {
        Warehouse warehouse = getRequired(id);
        verifyVersion(warehouse, expectedVersion);

        String actor = currentActorProvider.currentActor();
        Instant occurredAt = clock.instant();

        boolean structureChanged =
                warehouse.getPurpose() != purpose
                        || warehouse.getForm() != form
                        || warehouse.getManagementCategory() != managementCategory;

        if (structureChanged) {
            warehouse.updateStructure(purpose, form, managementCategory, actor, occurredAt);
        }

        warehouse.updateBasicInformation(name, remark, actor, occurredAt);
        return warehouse;
    }

    public Warehouse changeStatus(Long id, WarehouseStatus targetStatus, long expectedVersion) {
        Warehouse warehouse = getRequired(id);
        verifyVersion(warehouse, expectedVersion);

        String actor = currentActorProvider.currentActor();
        Instant occurredAt = clock.instant();

        if (targetStatus == WarehouseStatus.ENABLED) {
            warehouse.enable(actor, occurredAt);
        } else {
            warehouse.disable(actor, occurredAt);
        }

        return warehouse;
    }

    private Warehouse getRequired(Long id) {
        return repository
                .findByIdAndDeletedAtIsNull(id)
                .orElseThrow(
                        () ->
                                new WarehouseException(
                                        WarehouseErrorCode.WAREHOUSE_NOT_FOUND, "仓库不存在：" + id));
    }

    private static void verifyVersion(Warehouse warehouse, long expectedVersion) {
        if (warehouse.getVersion() != expectedVersion) {
            throw new WarehouseException(
                    WarehouseErrorCode.WAREHOUSE_VERSION_CONFLICT, "仓库数据已被其他操作修改，请刷新后重试");
        }
    }
}
