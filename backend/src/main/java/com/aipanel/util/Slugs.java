package com.aipanel.util;

import java.util.regex.Pattern;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.exception.ApiException;

/** 预设 slug 规则。 */
public final class Slugs {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final int MAX_LENGTH = 64;

    private Slugs() {
    }

    public static boolean isValid(String slug) {
        return slug != null && slug.length() <= MAX_LENGTH && PATTERN.matcher(slug).matches();
    }

    public static String requireValid(String slug) {
        if (!isValid(slug)) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        return slug;
    }
}
