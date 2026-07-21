package io.github.acczff.mdop.masterdata.warehouse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WarehouseTest {

    private static final String ACTOR = "i1-warehouse-test";
    private static final Instant CREATED_AT = Instant.parse("2026-07-21T08:00:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-07-21T09:00:00Z");

    @Test
    void createShouldNormalizeCodeAndEnableWarehouse() {
        Warehouse warehouse = newWarehouse();

        assertThat(warehouse.getCode()).isEqualTo("WH-0001");
        assertThat(warehouse.getName()).isEqualTo("常规原料仓");
        assertThat(warehouse.getPurpose()).isEqualTo(WarehousePurpose.RAW_MATERIAL);
        assertThat(warehouse.getForm()).isEqualTo(WarehouseForm.PHYSICAL);
        assertThat(warehouse.getManagementCategory())
                .isEqualTo(WarehouseManagementCategory.GENERAL);
        assertThat(warehouse.getStatus()).isEqualTo(WarehouseStatus.ENABLED);
        assertThat(warehouse.getRemark()).isEqualTo("常规电子元器件");
        assertThat(warehouse.getVersion()).isZero();
        assertThat(warehouse.getCreatedBy()).isEqualTo(ACTOR);
        assertThat(warehouse.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(warehouse.getUpdatedBy()).isEqualTo(ACTOR);
        assertThat(warehouse.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void enabledWarehouseShouldAllowBasicInformationUpdate() {
        Warehouse warehouse = newWarehouse();

        warehouse.updateBasicInformation(" 常规电子原料仓 ", " 名称调整 ", ACTOR, CHANGED_AT);

        assertThat(warehouse.getName()).isEqualTo("常规电子原料仓");
        assertThat(warehouse.getRemark()).isEqualTo("名称调整");
        assertThat(warehouse.getUpdatedBy()).isEqualTo(ACTOR);
        assertThat(warehouse.getUpdatedAt()).isEqualTo(CHANGED_AT);
    }

    @Test
    void enabledWarehouseShouldRejectStructureChange() {
        Warehouse warehouse = newWarehouse();

        assertThatThrownBy(
                        () ->
                                warehouse.updateStructure(
                                        WarehousePurpose.LINE_SIDE,
                                        WarehouseForm.LOGICAL,
                                        WarehouseManagementCategory.GENERAL,
                                        ACTOR,
                                        CHANGED_AT))
                .isInstanceOfSatisfying(
                        WarehouseException.class,
                        exception ->
                                assertThat(exception.getCode())
                                        .isEqualTo(
                                                WarehouseErrorCode
                                                        .WAREHOUSE_STRUCTURE_CHANGE_REQUIRES_DISABLED));
    }

    @Test
    void disabledWarehouseShouldAllowStructureChangeAndReEnable() {
        Warehouse warehouse = newWarehouse();

        warehouse.disable(ACTOR, CHANGED_AT);
        warehouse.updateStructure(
                WarehousePurpose.LINE_SIDE,
                WarehouseForm.LOGICAL,
                WarehouseManagementCategory.GENERAL,
                ACTOR,
                CHANGED_AT.plusSeconds(1));
        warehouse.enable(ACTOR, CHANGED_AT.plusSeconds(2));

        assertThat(warehouse.getCode()).isEqualTo("WH-0001");
        assertThat(warehouse.getPurpose()).isEqualTo(WarehousePurpose.LINE_SIDE);
        assertThat(warehouse.getForm()).isEqualTo(WarehouseForm.LOGICAL);
        assertThat(warehouse.getStatus()).isEqualTo(WarehouseStatus.ENABLED);
    }

    private static Warehouse newWarehouse() {
        return Warehouse.create(
                " wh-0001 ",
                " 常规原料仓 ",
                WarehousePurpose.RAW_MATERIAL,
                WarehouseForm.PHYSICAL,
                WarehouseManagementCategory.GENERAL,
                " 常规电子元器件 ",
                ACTOR,
                CREATED_AT);
    }
}
