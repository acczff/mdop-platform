package io.github.acczff.mdop.masterdata.warehouse.infrastructure;

import io.github.acczff.mdop.masterdata.warehouse.application.WarehouseSearchCriteria;
import io.github.acczff.mdop.masterdata.warehouse.domain.Warehouse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class WarehouseSpecifications {

    private WarehouseSpecifications() {}

    public static Specification<Warehouse> matching(WarehouseSearchCriteria criteria) {
        List<Specification<Warehouse>> specifications = new ArrayList<>();

        specifications.add((root, query, builder) -> builder.isNull(root.get("deletedAt")));

        if (criteria.keyword() != null) {
            String pattern = "%" + escapeLike(criteria.keyword().toLowerCase(Locale.ROOT)) + "%";

            specifications.add(
                    (root, query, builder) ->
                            builder.or(
                                    builder.like(
                                            builder.lower(root.<String>get("code")), pattern, '\\'),
                                    builder.like(
                                            builder.lower(root.<String>get("name")),
                                            pattern,
                                            '\\')));
        }

        if (criteria.purpose() != null) {
            specifications.add(
                    (root, query, builder) ->
                            builder.equal(root.get("purpose"), criteria.purpose()));
        }

        if (criteria.form() != null) {
            specifications.add(
                    (root, query, builder) -> builder.equal(root.get("form"), criteria.form()));
        }

        if (criteria.managementCategory() != null) {
            specifications.add(
                    (root, query, builder) ->
                            builder.equal(
                                    root.get("managementCategory"), criteria.managementCategory()));
        }

        if (criteria.status() != null) {
            specifications.add(
                    (root, query, builder) -> builder.equal(root.get("status"), criteria.status()));
        }

        return Specification.allOf(specifications);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
