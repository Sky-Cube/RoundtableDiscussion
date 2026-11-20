package com.aipanel.model;

/** 实录发言行。P05 只读取已提交记录，不写入。 */
public record Utterance(
        int seq,
        String participantId,
        String kind,
        String content,
        Integer replyToUtteranceSeq,
        String createdAt) {
}
