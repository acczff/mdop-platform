package io.github.acczff.mdop.masterdata.warehouse.infrastructure;

import io.github.acczff.mdop.masterdata.warehouse.domain.Warehouse;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WarehouseRepository
        extends JpaRepository<Warehouse, Long>, JpaSpecificationExecutor<Warehouse> {

    boolean existsByCode(String code);

    Optional<Warehouse> findByIdAndDeletedAtIsNull(Long id);
}
