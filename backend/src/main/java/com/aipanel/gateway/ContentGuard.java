package com.aipanel.gateway;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class ContentGuard {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+86[ -]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern LABELED_PHONE = Pattern.compile(
            "(?:电话|手机|联系方式|phone|tel)\\s*[:：]?\\s*(?:\\+?\\d[ -]?){7,15}", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile("(?:身份证(?:号)?|证件号)\\s*[:：]?\\s*\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----|\\b(?:sk-[a-z0-9_-]{12,}|AKIA[A-Z0-9]{16})\\b"
            + "|(?:bearer\\s+|(?:api[_ -]?key|access[_ -]?token|password|密码)\\s*[:=：]\\s*)[\\w./+=-]{8,}");
    private static final Pattern CONTROL = Pattern.compile(
            "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(?:邮箱|电话|证件号)\\]");
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String ID_CHECK_VALUES = "10X98765432";

    public String safeTopic(String input) {
        if (input == null || CONTROL.matcher(input).find() || SECRET.matcher(input).find()) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        String safe = redact(input);
        if (PLACEHOLDER.matcher(safe).replaceAll("").codePoints().noneMatch(Character::isLetterOrDigit)
                && !safe.equals(input)) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        return safe;
    }

    public String safeContext(String input) {
        if (input != null && !CONTROL.matcher(input).find() && !SECRET.matcher(input).find()) {
            return redact(input);
        }
        throw ModelGatewayException.blocked();
    }

    public void checkOutput(JsonNode value) {
        if (value.isTextual() && !safeContext(value.asText()).equals(value.asText())) {
            throw ModelGatewayException.blocked();
        }
        value.forEach(this::checkOutput);
    }

    private static String redact(String input) {
        String safe = EMAIL.matcher(input).replaceAll("[邮箱]");
        safe = ID.matcher(safe).replaceAll(match -> {
            String value = match.group();
            String digits = value.substring(value.length() - 18);
            int sum = 0;
            for (int index = 0; index < ID_WEIGHTS.length; index++) {
                sum += (digits.charAt(index) - '0') * ID_WEIGHTS[index];
            }
            return ID_CHECK_VALUES.charAt(sum % 11) == Character.toUpperCase(digits.charAt(17))
                    ? "[证件号]" : java.util.regex.Matcher.quoteReplacement(value);
        });
        safe = LABELED_PHONE.matcher(safe).replaceAll("[电话]");
        return PHONE.matcher(safe).replaceAll("[电话]");
    }
}
