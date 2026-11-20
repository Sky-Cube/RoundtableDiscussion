package com.aipanel.service.debate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aipanel.model.Discussion;
import com.aipanel.model.Insight;
import com.aipanel.model.Participant;
import com.aipanel.model.Utterance;

public record DiscussionContext(Discussion room, List<Participant> members, List<Utterance> transcript,
        List<Insight> insights, int insightEventSeq) {

    public int version() {
        return transcript.isEmpty() ? 0 : transcript.getLast().seq();
    }

    public List<Utterance> expertLines() {
        return transcript.stream().filter(line -> "EXPERT".equals(line.kind())).toList();
    }

    public Participant host() {
        return members.stream().filter(member -> "HOST".equals(member.role())).findFirst().orElseThrow();
    }

    public List<Participant> experts() {
        return members.stream().filter(member -> "EXPERT".equals(member.role())).toList();
    }

    public Map<String, Object> base(String phase) {
        Map<String, Object> input = new HashMap<>();
        input.put("discussionGoal", room.discussionGoal());
        input.put("phase", phase);
        input.put("topic", room.topic());
        return input;
    }

    public Map<String, Object> role(Participant member) {
        Map<String, Object> role = new HashMap<>();
        role.put("participantId", member.id());
        role.put("displayName", member.displayName());
        role.put("profession", member.profession());
        role.put("title", member.title());
        role.put("stance", member.stance());
        role.put("perspective", member.perspective());
        return role;
    }

    public Map<String, Object> line(Utterance line) {
        Map<String, Object> value = new HashMap<>();
        value.put("seq", line.seq());
        value.put("participantId", line.participantId());
        members.stream().filter(member -> member.id().equals(line.participantId())).findFirst()
                .ifPresent(member -> value.put("speakerName", member.displayName()));
        value.put("role", "EXPERT".equals(line.kind()) ? "EXPERT" : "HOST");
        value.put("kind", line.kind());
        value.put("content", line.content());
        value.put("replyToUtteranceSeq", line.replyToUtteranceSeq());
        return value;
    }

    public void projection(Map<String, Object> input) {
        input.put("insightStatus", room.insightStatus());
        input.put("insightsThroughUtteranceSeq",
                room.insightsThroughUtteranceSeq() == null ? 0 : room.insightsThroughUtteranceSeq());
    }
}
