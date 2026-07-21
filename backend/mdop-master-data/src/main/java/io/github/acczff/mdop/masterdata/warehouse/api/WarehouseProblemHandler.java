package io.github.acczff.mdop.masterdata.warehouse.api;

import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseErrorCode;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = WarehouseController.class)
public class WarehouseProblemHandler {

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    @ExceptionHandler(WarehouseException.class)
    public ResponseEntity<ProblemDetail> handleWarehouseException(
            WarehouseException exception, HttpServletRequest request) {
        HttpStatus status =
                switch (exception.getCode()) {
                    case WAREHOUSE_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case WAREHOUSE_CODE_DUPLICATE,
                            WAREHOUSE_STRUCTURE_CHANGE_REQUIRES_DISABLED,
                            WAREHOUSE_VERSION_CONFLICT ->
                            HttpStatus.CONFLICT;
                };

        String title =
                switch (exception.getCode()) {
                    case WAREHOUSE_NOT_FOUND -> "仓库不存在";
                    case WAREHOUSE_CODE_DUPLICATE -> "仓库编码冲突";
                    case WAREHOUSE_STRUCTURE_CHANGE_REQUIRES_DISABLED -> "仓库状态冲突";
                    case WAREHOUSE_VERSION_CONFLICT -> "仓库版本冲突";
                };

        return response(
                problem(
                        status,
                        title,
                        exception.getMessage(),
                        exception.getCode().name(),
                        request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<Map<String, String>> fieldErrors =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                        .toList();

        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "请求参数校验失败",
                        "请求体中存在无效字段",
                        VALIDATION_FAILED,
                        request);
        problem.setProperty("fieldErrors", fieldErrors);

        return response(problem);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        List<Map<String, String>> fieldErrors =
                exception.getParameterValidationResults().stream()
                        .flatMap(
                                result -> {
                                    String parameterName =
                                            result.getMethodParameter().getParameterName();
                                    if (parameterName == null) {
                                        parameterName =
                                                "argument"
                                                        + result.getMethodParameter()
                                                                .getParameterIndex();
                                    }

                                    String field = parameterName;
                                    return result.getResolvableErrors().stream()
                                            .map(
                                                    error ->
                                                            fieldError(
                                                                    field,
                                                                    error.getDefaultMessage()));
                                })
                        .toList();

        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "请求参数校验失败",
                        "请求参数不符合约束",
                        VALIDATION_FAILED,
                        request);
        problem.setProperty("fieldErrors", fieldErrors);

        return response(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<Map<String, String>> fieldErrors =
                exception.getConstraintViolations().stream()
                        .map(
                                violation ->
                                        fieldError(
                                                lastPathSegment(
                                                        violation.getPropertyPath().toString()),
                                                violation.getMessage()))
                        .toList();

        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "请求参数校验失败",
                        "请求参数不符合约束",
                        VALIDATION_FAILED,
                        request);
        problem.setProperty("fieldErrors", fieldErrors);

        return response(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return response(
                problem(
                        HttpStatus.BAD_REQUEST,
                        "请求参数校验失败",
                        "参数 " + exception.getName() + " 的值无效",
                        VALIDATION_FAILED,
                        request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(
                problem(
                        HttpStatus.BAD_REQUEST,
                        "请求参数校验失败",
                        "请求体格式不正确或包含非法枚举值",
                        VALIDATION_FAILED,
                        request));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLocking(
            ObjectOptimisticLockingFailureException exception, HttpServletRequest request) {
        return response(
                problem(
                        HttpStatus.CONFLICT,
                        "仓库版本冲突",
                        "仓库数据已被其他操作修改，请刷新后重试",
                        WarehouseErrorCode.WAREHOUSE_VERSION_CONFLICT.name(),
                        request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        String message = exception.getMostSpecificCause().getMessage();
        String normalizedMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);

        if (!normalizedMessage.contains("uk_mdm_warehouse_code")
                && !normalizedMessage.contains("duplicate entry")) {
            throw exception;
        }

        return response(
                problem(
                        HttpStatus.CONFLICT,
                        "仓库编码冲突",
                        "仓库编码已存在",
                        WarehouseErrorCode.WAREHOUSE_CODE_DUPLICATE.name(),
                        request));
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String code,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static Map<String, String> fieldError(String field, String message) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("field", field);
        result.put("message", message == null ? "字段值不合法" : message);
        return result;
    }

    private static String lastPathSegment(String path) {
        int separatorIndex = path.lastIndexOf('.');
        return separatorIndex < 0 ? path : path.substring(separatorIndex + 1);
    }
}
