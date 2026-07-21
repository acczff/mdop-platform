package io.github.acczff.mdop.masterdata.warehouse.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WarehouseSearchCriteriaTest {

    @Test
    void shouldTrimKeyword() {
        WarehouseSearchCriteria criteria =
                new WarehouseSearchCriteria("  原料仓  ", null, null, null, null, 1, 20);

        assertThat(criteria.keyword()).isEqualTo("原料仓");
    }

    @Test
    void shouldConvertBlankKeywordToNull() {
        WarehouseSearchCriteria criteria =
                new WarehouseSearchCriteria("   ", null, null, null, null, 1, 20);

        assertThat(criteria.keyword()).isNull();
    }

    @Test
    void shouldRejectInvalidPagination() {
        assertThatThrownBy(() -> new WarehouseSearchCriteria(null, null, null, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("页码必须从1开始");

        assertThatThrownBy(() -> new WarehouseSearchCriteria(null, null, null, null, null, 1, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("每页数量必须在1到100之间");
    }
}
