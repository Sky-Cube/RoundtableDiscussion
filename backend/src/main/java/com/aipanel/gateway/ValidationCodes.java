package com.aipanel.gateway;

/** 阵容校验码，同时作为 R08 补偿的校验提示。 */
public final class ValidationCodes {

    public static final String GOAL_MISSING = "GOAL_MISSING";
    public static final String GOAL_INVALID = "GOAL_INVALID";
    public static final String PARTICIPANT_COUNT = "PARTICIPANT_COUNT";
    public static final String HOST_NOT_FIRST = "HOST_NOT_FIRST";
    public static final String HOST_COUNT = "HOST_COUNT";
    public static final String HOST_FIELD_INVALID = "HOST_FIELD_INVALID";
    public static final String ROLE_INVALID = "ROLE_INVALID";
    public static final String PERSPECTIVE_INVALID = "PERSPECTIVE_INVALID";
    public static final String PERSPECTIVE_DUPLICATE = "PERSPECTIVE_DUPLICATE";
    public static final String STANCE_INVALID = "STANCE_INVALID";
    public static final String NAME_DUPLICATE = "NAME_DUPLICATE";
    public static final String FIELD_LENGTH_INVALID = "FIELD_LENGTH_INVALID";
    public static final String LOW_QUALITY = "LOW_QUALITY";

    private ValidationCodes() {
    }
}
