package com.aipanel.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 议题文本规范化：NFC 归一、去零宽字符、控制字符转空格、空白折叠，长度按 Unicode 码点计。
 */
public final class TopicNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TopicNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder builder = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (isZeroWidth(codePoint)) {
                return;
            }
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                builder.append(' ');
                return;
            }
            builder.appendCodePoint(codePoint);
        });
        return WHITESPACE.matcher(builder).replaceAll(" ").trim();
    }

    public static int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static boolean isZeroWidth(int codePoint) {
        return codePoint == 0xFEFF
                || (codePoint >= 0x200B && codePoint <= 0x200F)
                || codePoint == 0x2060
                || codePoint == 0x00AD;
    }
}
