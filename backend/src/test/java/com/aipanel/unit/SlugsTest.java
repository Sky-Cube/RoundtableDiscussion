package com.aipanel.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aipanel.util.Slugs;

import static org.assertj.core.api.Assertions.assertThat;

/** 预设 slug 正则。 */
class SlugsTest {

    @Test
    @DisplayName("小写字母、数字与单连字符合法")
    void acceptsValidSlug() {
        assertThat(Slugs.isValid("hybrid-work-policy")).isTrue();
        assertThat(Slugs.isValid("a1-b2")).isTrue();
    }

    @Test
    @DisplayName("大写、下划线、连续连字符与首尾连字符非法")
    void rejectsInvalidSlug() {
        assertThat(Slugs.isValid("Bad_Preset")).isFalse();
        assertThat(Slugs.isValid("two--dash")).isFalse();
        assertThat(Slugs.isValid("-leading")).isFalse();
        assertThat(Slugs.isValid("")).isFalse();
        assertThat(Slugs.isValid(null)).isFalse();
    }
}