package com.aipanel.util;

import java.util.UUID;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.exception.ApiException;

/** 路径标识校验。 */
public final class Uuids {

    private Uuids() {
    }

    public static String requireId(String value) {
        if (value == null) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException error) {
            throw new ApiException(ApiErrorCode.B0001);
        }
    }
}
