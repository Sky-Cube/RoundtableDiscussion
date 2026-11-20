package com.aipanel.config;

import java.nio.file.Path;

/** 校验通过后的 SQLite 数据库位置。 */
public record SqliteDatabase(Path path, Path allowedRoot) {
}
