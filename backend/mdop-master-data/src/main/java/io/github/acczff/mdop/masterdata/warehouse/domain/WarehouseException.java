package io.github.acczff.mdop.masterdata.warehouse.domain;

import java.io.Serial;

public class WarehouseException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final WarehouseErrorCode code;

    public WarehouseException(WarehouseErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public WarehouseErrorCode getCode() {
        return code;
    }
}
