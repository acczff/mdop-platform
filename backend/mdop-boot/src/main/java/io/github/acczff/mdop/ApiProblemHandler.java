package io.github.acczff.mdop;

import io.github.acczff.mdop.common.BusinessException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiProblemHandler {
    @ExceptionHandler(BusinessException.class)
    ProblemDetail business(BusinessException exception) {
        return problem(exception.getStatus(), exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        var result = problem(400, "VALIDATION_FAILED", "请检查必填项、数量及字段长度");
        result.setProperty(
                "fieldErrors",
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        Map.of(
                                                "field",
                                                error.getField(),
                                                "message",
                                                error.getDefaultMessage() == null
                                                        ? "无效参数"
                                                        : error.getDefaultMessage()))
                        .toList());
        return result;
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ProblemDetail malformed(Exception exception) {
        return problem(400, "VALIDATION_FAILED", "请求格式或参数类型不正确");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException exception) {
        return problem(409, "DATA_CONFLICT", "编码、外部单号或幂等键已存在，或关联数据不允许此操作");
    }

    private ProblemDetail problem(int status, String code, String detail) {
        var result =
                ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatusCode.valueOf(status), detail);
        result.setProperty("code", code);
        return result;
    }
}
