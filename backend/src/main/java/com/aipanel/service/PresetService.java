package com.aipanel.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.exception.ApiException;
import com.aipanel.model.Discussion;
import com.aipanel.model.Participant;
import com.aipanel.model.Preset;
import com.aipanel.model.PresetParticipant;
import com.aipanel.repository.DiscussionRepository;
import com.aipanel.repository.ParticipantRepository;
import com.aipanel.repository.PresetRepository;
import com.aipanel.util.Ids;
import com.aipanel.util.Slugs;
import com.aipanel.util.TopicNormalizer;
import com.aipanel.web.vo.DiscussionSnapshotVO;
import com.aipanel.web.vo.PresetParticipantVO;
import com.aipanel.web.vo.PresetVO;

/** 预设读取与复制。复制不使用模型网关，只复制已保存的议题、目标与阵容。 */
@Service
public class PresetService {

    private final PresetRepository presets;
    private final DiscussionRepository discussions;
    private final ParticipantRepository participants;
    private final DiscussionService discussionService;
    private final TransactionTemplate transactions;

    public PresetService(PresetRepository presets, DiscussionRepository discussions,
            ParticipantRepository participants, DiscussionService discussionService,
            TransactionTemplate transactions) {
        this.presets = presets;
        this.discussions = discussions;
        this.participants = participants;
        this.discussionService = discussionService;
        this.transactions = transactions;
    }

    public List<PresetVO> list() {
        List<PresetVO> result = new ArrayList<>();
        for (Preset preset : presets.findAll()) {
            requireHealthy(preset);
            result.add(toVO(preset));
        }
        return List.copyOf(result);
    }

    /** 复制预设生成独立草稿：新房间 ID、新成员 ID，状态 DRAFT，lastEventSeq 为 0。 */
    public DiscussionSnapshotVO copy(String slug) {
        Slugs.requireValid(slug);
        Preset preset = presets.findBySlug(slug).orElseThrow(() -> new ApiException(ApiErrorCode.B0002));
        requireHealthy(preset);

        String roomId = Ids.uuid();
        Discussion discussion = new Discussion(roomId, preset.topic(), preset.discussionGoal(), preset.expertCount(),
                "DRAFT", 0, "PENDING", null, Ids.now(), null, null, null);
        List<Participant> members = toParticipants(roomId, preset);

        transactions.executeWithoutResult(status -> {
            discussions.insert(discussion);
            participants.insertAll(members);
        });
        return discussionService.get(roomId);
    }

    /** 预设完整性校验：恰好一位主持、专家人数与目标人数一致、视角互异且立场有效。 */
    private void requireHealthy(Preset preset) {
        if (preset.topic() == null) {
            throw unhealthy("预设缺少议题：" + preset.slug());
        }
        int topicLength = TopicNormalizer.codePointLength(preset.topic().trim());
        if (topicLength < 3 || topicLength > 120) {
            throw unhealthy("预设议题长度越界：" + preset.slug());
        }
        if (!RosterValidator.GOALS.contains(preset.discussionGoal())) {
            throw unhealthy("预设讨论目标非法：" + preset.slug());
        }
        List<PresetParticipant> members = preset.participants() == null ? List.of() : preset.participants();
        long hosts = members.stream().filter(member -> "HOST".equals(member.role())).count();
        long experts = members.stream().filter(member -> "EXPERT".equals(member.role())).count();
        if (hosts != 1) {
            throw unhealthy("预设必须恰好一位主持：" + preset.slug());
        }
        if (experts < 2 || experts > 6 || experts != preset.expertCount()) {
            throw unhealthy("预设专家人数与目标不一致：" + preset.slug());
        }
        Set<String> perspectives = new HashSet<>();
        for (PresetParticipant member : members) {
            boolean host = "HOST".equals(member.role());
            if (!host && !"EXPERT".equals(member.role())) {
                throw unhealthy("预设存在非法角色：" + preset.slug());
            }
            if (host) {
                if (member.stance() != null || member.perspective() != null) {
                    throw unhealthy("预设主持不得携带立场或视角：" + preset.slug());
                }
                continue;
            }
            if (member.perspective() == null || !RosterValidator.PERSPECTIVES.contains(member.perspective())
                    || !perspectives.add(member.perspective())) {
                throw unhealthy("预设专家视角非法或重复：" + preset.slug());
            }
            int stanceLength = member.stance() == null ? 0
                    : TopicNormalizer.codePointLength(member.stance().trim());
            if (stanceLength < 8 || stanceLength > 100) {
                throw unhealthy("预设专家立场长度越界：" + preset.slug());
            }
        }
    }

    private ApiException unhealthy(String reason) {
        return new ApiException(ApiErrorCode.B0005, "预设数据已损坏，暂时无法读取");
    }

    private List<Participant> toParticipants(String roomId, Preset preset) {
        List<Participant> members = new ArrayList<>(preset.participants().size());
        int expertIndex = 0;
        for (PresetParticipant member : preset.participants()) {
            if ("HOST".equals(member.role())) {
                members.add(new Participant(roomId, "copy-host", "HOST", member.displayName(), member.profession(),
                        member.title(), null, null, member.colorKey(), "IDLE", null));
            } else {
                members.add(new Participant(roomId, "copy-e" + (++expertIndex), "EXPERT", member.displayName(),
                        member.profession(), member.title(), member.stance(), member.perspective(), member.colorKey(),
                        "IDLE", null));
            }
        }
        return members;
    }

    private PresetVO toVO(Preset preset) {
        List<PresetParticipantVO> members = preset.participants().stream()
                .map(member -> new PresetParticipantVO(member.id(), member.role(), member.displayName(),
                        member.profession(), member.title(), member.stance(), member.perspective(), member.colorKey()))
                .toList();
        return new PresetVO(preset.slug(), preset.topic(), preset.discussionGoal(), preset.expertCount(), members);
    }
}
