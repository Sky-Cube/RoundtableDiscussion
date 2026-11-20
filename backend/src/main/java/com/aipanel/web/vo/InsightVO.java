package com.aipanel.web.vo;

import java.util.List;

/** 公开结论投影。 */
public record InsightVO(String id, String kind, String text, List<Integer> evidenceUtteranceSeqs) {
}
