package io.github.acczff.mdop.masterdata.warehouse.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.warehouse.domain.Warehouse;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseErrorCode;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseException;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseForm;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseManagementCategory;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehousePurpose;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseStatus;
import io.github.acczff.mdop.masterdata.warehouse.infrastructure.WarehouseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

    private static final String ACTOR = "i1-service-test";
    private static final Instant NOW = Instant.parse("2026-07-21T10:00:00Z");

    @Mock private WarehouseRepository repository;

    private WarehouseService service;

    @BeforeEach
    void setUp() {
        CurrentActorProvider actorProvider = () -> ACTOR;
        service = new WarehouseService(repository, actorProvider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createShouldSaveNormalizedWarehouse() {
        when(repository.existsByCode("WH-0001")).thenReturn(false);
        when(repository.save(any(Warehouse.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Warehouse warehouse =
                service.create(
                        " wh-0001 ",
                        " 常规原料仓 ",
                        WarehousePurpose.RAW_MATERIAL,
                        WarehouseForm.PHYSICAL,
                        WarehouseManagementCategory.GENERAL,
                        null);

        assertThat(warehouse.getCode()).isEqualTo("WH-0001");
        assertThat(warehouse.getStatus()).isEqualTo(WarehouseStatus.ENABLED);
        assertThat(warehouse.getCreatedBy()).isEqualTo(ACTOR);
        assertThat(warehouse.getCreatedAt()).isEqualTo(NOW);
        verify(repository).save(warehouse);
    }

    @Test
    void createShouldRejectDuplicateCode() {
        when(repository.existsByCode("WH-0001")).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                service.create(
                                        "WH-0001",
                                        "常规原料仓",
                                        WarehousePurpose.RAW_MATERIAL,
                                        WarehouseForm.PHYSICAL,
                                        WarehouseManagementCategory.GENERAL,
                                        null))
                .isInstanceOfSatisfying(
                        WarehouseException.class,
                        exception ->
                                assertThat(exception.getCode())
                                        .isEqualTo(WarehouseErrorCode.WAREHOUSE_CODE_DUPLICATE));

        verify(repository, never()).save(any(Warehouse.class));
    }

    @Test
    void updateShouldRejectStaleVersion() {
        Warehouse warehouse = newWarehouse();
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(warehouse));

        assertThatThrownBy(
                        () ->
                                service.update(
                                        1L,
                                        "常规原料仓",
                                        WarehousePurpose.RAW_MATERIAL,
                                        WarehouseForm.PHYSICAL,
                                        WarehouseManagementCategory.GENERAL,
                                        null,
                                        1L))
                .isInstanceOfSatisfying(
                        WarehouseException.class,
                        exception ->
                                assertThat(exception.getCode())
                                        .isEqualTo(WarehouseErrorCode.WAREHOUSE_VERSION_CONFLICT));
    }

    @Test
    void updateShouldAllowBasicInformationChangeWhileEnabled() {
        Warehouse warehouse = newWarehouse();
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(warehouse));

        Warehouse updated =
                service.update(
                        1L,
                        "常规电子原料仓",
                        WarehousePurpose.RAW_MATERIAL,
                        WarehouseForm.PHYSICAL,
                        WarehouseManagementCategory.GENERAL,
                        "名称调整",
                        0L);

        assertThat(updated.getName()).isEqualTo("常规电子原料仓");
        assertThat(updated.getRemark()).isEqualTo("名称调整");
        assertThat(updated.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void changeStatusShouldDisableWarehouse() {
        Warehouse warehouse = newWarehouse();
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(warehouse));

        Warehouse updated = service.changeStatus(1L, WarehouseStatus.DISABLED, 0L);

        assertThat(updated.getStatus()).isEqualTo(WarehouseStatus.DISABLED);
        assertThat(updated.getUpdatedBy()).isEqualTo(ACTOR);
    }

    @Test
    void searchShouldConvertOneBasedPageAndUseCodeSort() {
        when(repository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<Warehouse>>any(),
                        any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<Warehouse> result =
                service.search(
                        new WarehouseSearchCriteria(
                                "原料",
                                WarehousePurpose.RAW_MATERIAL,
                                null,
                                null,
                                WarehouseStatus.ENABLED,
                                2,
                                20));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(repository)
                .findAll(
                        org.mockito.ArgumentMatchers.<Specification<Warehouse>>any(),
                        pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();

        assertThat(result).isEmpty();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().getOrderFor("code"))
                .isNotNull()
                .satisfies(order -> assertThat(order.isAscending()).isTrue());
    }

    private static Warehouse newWarehouse() {
        return Warehouse.create(
                "WH-0001",
                "常规原料仓",
                WarehousePurpose.RAW_MATERIAL,
                WarehouseForm.PHYSICAL,
                WarehouseManagementCategory.GENERAL,
                null,
                "creator",
                Instant.parse("2026-07-21T08:00:00Z"));
    }
}
