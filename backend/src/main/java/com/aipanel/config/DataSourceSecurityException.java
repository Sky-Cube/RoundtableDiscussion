package com.aipanel.config;

/** 数据源路径守卫拒绝打开目标库时抛出，映射为 B0005。 */
public class DataSourceSecurityException extends RuntimeException {

    public DataSourceSecurityException(String message) {
        super(message);
    }
}
