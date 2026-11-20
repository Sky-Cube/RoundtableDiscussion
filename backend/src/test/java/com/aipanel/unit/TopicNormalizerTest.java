package com.aipanel.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aipanel.util.TopicNormalizer;

import static org.assertj.core.api.Assertions.assertThat;

/** 议题规范化：NFC、零宽字符、控制字符与码点计数。 */
class TopicNormalizerTest {

    @Test
    @DisplayName("NFC 合并组合字符，控制字符转空格后折叠空白")
    void normalizesNfcAndWhitespace() {
        String normalized = TopicNormalizer.normalize("  Cafe\u0301\t换行\n示例  ");

        assertThat(normalized).isEqualTo("Café 换行 示例");
    }

    @Test
    @DisplayName("去掉 BOM 与零宽字符")
    void stripsBomAndZeroWidth() {
        String normalized = TopicNormalizer.normalize("\uFEFFAI\u200B客服\u200D议题");

        assertThat(normalized).isEqualTo("AI客服议题");
    }

    @Test
    @DisplayName("长度按 Unicode 码点计数，不按 UTF-16 单元")
    void countsCodePoints() {
        assertThat(TopicNormalizer.codePointLength("😀")).isEqualTo(1);
        assertThat(TopicNormalizer.codePointLength("😀😀😀")).isEqualTo(3);
        assertThat(TopicNormalizer.normalize("😀\u0000")).isEqualTo("😀");
    }
}