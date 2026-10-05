package io.github.acczff.mdop.common;

import java.util.Locale;

public final class BusinessText {
    private BusinessText() {}

    public static String required(String value, String name, int max) {
        String result = value == null ? "" : value.trim().strip();
        if (result.isBlank()
                || result.length() > max
                || result.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(400, "VALIDATION_FAILED", name + "不能为空、超长或包含控制字符");
        }
        return result;
    }

    public static String code(String value) {
        String result = required(value, "编码", 32).toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z][A-Z0-9-]{1,31}"))
            throw new BusinessException(400, "VALIDATION_FAILED", "编码应以字母开头，仅包含字母、数字和短横线，长度2到32");
        return result;
    }

    public static String optional(String value, String name, int max) {
        return value == null || value.isBlank() ? "" : required(value, name, max);
    }
}
