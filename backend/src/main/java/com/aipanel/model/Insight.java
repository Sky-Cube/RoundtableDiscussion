package com.aipanel.model;

import java.util.List;

/** 结论行及其证据序号。P05 只读取。 */
public record Insight(String id, String kind, String text, List<Integer> evidenceUtteranceSeqs) {
}
