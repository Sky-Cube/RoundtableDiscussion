package com.aipanel.web.vo;

/** 公开实录发言。 */
public record UtteranceVO(int seq, String participantId, String kind, String content,
        Integer replyToUtteranceSeq, String createdAt) {
}
