package com.aipanel.service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.exception.ApiException;
import com.aipanel.model.Discussion;
import com.aipanel.repository.DiscussionEventRepository;
import com.aipanel.repository.DiscussionRepository;
import com.aipanel.repository.InsightRepository;
import com.aipanel.repository.ParticipantRepository;
import com.aipanel.repository.UtteranceRepository;
import com.aipanel.util.TopicNormalizer;
import com.aipanel.util.Uuids;
import com.aipanel.web.vo.AdminDiscussionVO;
import com.aipanel.web.vo.AdminEventVO;
import com.aipanel.web.vo.AdminOverviewVO;
import com.aipanel.web.vo.AdminRoomVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 后台管理查询：总览、按名称/日期检索房间、房间详情与事件流。 */
@Service
public class AdminService {

    private static final Set<String> STATUSES = Set.of("DRAFT", "RUNNING", "SUMMARIZING", "COMPLETED", "FAILED");
    private static final int MAX_KEYWORD_LENGTH = 60;

    private final DiscussionRepository discussions;
    private final ParticipantRepository participants;
    private final UtteranceRepository utterances;
    private final InsightRepository insights;
    private final DiscussionEventRepository events;
    private final DiscussionService discussionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ZoneId zoneId = ZoneId.systemDefault();

    public AdminService(DiscussionRepository discussions, ParticipantRepository participants,
            UtteranceRepository utterances, InsightRepository insights, DiscussionEventRepository events,
            DiscussionService discussionService, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.discussions = discussions;
        this.participants = participants;
        this.utterances = utterances;
        this.insights = insights;
        this.events = events;
        this.discussionService = discussionService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AdminOverviewVO overview() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String status : List.of("DRAFT", "RUNNING", "SUMMARIZING", "COMPLETED", "FAILED")) {
            int count = discussions.countByStatus(status);
            counts.put(status, count);
            total += count;
        }
        return new AdminOverviewVO(counts, total, count("SELECT COUNT(*) FROM preset"),
                count("SELECT COUNT(*) FROM utterance"), count("SELECT COUNT(*) FROM insight"),
                count("SELECT COUNT(*) FROM discussion_event"));
    }

    /**
     * 检索房间。
     *
     * @param status  ALL 或五种状态之一，大小写不敏感
     * @param keyword 议题子串，按 NFKC 小写比较；空串表示不过滤
     * @param date    `yyyy-MM-dd`，按服务器本地时区匹配房间创建日期；已开始的房间也匹配开始日期
     */
    public List<AdminDiscussionVO> search(String status, String keyword, String date) {
        String normalizedStatus = normalizeStatus(status);
        String needle = normalizeKeyword(keyword);
        LocalDate day = parseDate(date);

        List<AdminDiscussionVO> result = new ArrayList<>();
        for (Discussion room : discussions.findAll(normalizedStatus)) {
            if (!needle.isEmpty() && !normalizeText(room.topic()).contains(needle)) {
                continue;
            }
            if (day != null && !matchesDate(room, day)) {
                continue;
            }
            result.add(toVO(room));
        }
        return List.copyOf(result);
    }

    public AdminRoomVO detail(String id) {
        Uuids.requireId(id);
        List<AdminEventVO> stream = new ArrayList<>();
        for (DiscussionEventRepository.EventRow row : events.findAfter(id, 0)) {
            stream.add(new AdminEventVO(row.seq(), row.type(), row.createdAt(), parse(row.payloadJson())));
        }
        return new AdminRoomVO(discussionService.get(id), List.copyOf(stream));
    }

    private String normalizeStatus(String status) {
        String value = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();
        if (!"ALL".equals(value) && !STATUSES.contains(value)) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        return value;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String trimmed = keyword.trim();
        if (TopicNormalizer.codePointLength(trimmed) > MAX_KEYWORD_LENGTH) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        return normalizeText(trimmed);
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException error) {
            throw new ApiException(ApiErrorCode.B0001);
        }
    }

    private boolean matchesDate(Discussion room, LocalDate day) {
        return day.equals(localDate(room.createdAt())) || day.equals(localDate(room.startedAt()));
    }

    private LocalDate localDate(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp).atZone(zoneId).toLocalDate();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private AdminDiscussionVO toVO(Discussion room) {
        return new AdminDiscussionVO(room.id(), room.topic(), room.discussionGoal(), room.expertCount(),
                room.status(), room.createdAt(), room.startedAt(), room.endedAt(),
                ApiErrorCode.fromStoredCode(room.errorCode()),
                room.lastEventSeq(), participants.countByDiscussion(room.id()),
                utterances.countByDiscussion(room.id()), insights.countByDiscussion(room.id()),
                events.countByDiscussion(room.id()));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException error) {
            return objectMapper.createObjectNode();
        }
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
