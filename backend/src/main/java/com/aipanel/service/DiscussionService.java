package com.aipanel.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.aipanel.config.FaultInjector;
import com.aipanel.config.PanelProperties;
import com.aipanel.exception.ApiErrorCode;
import com.aipanel.exception.ApiException;
import com.aipanel.gateway.ModelGateway;
import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RosterOutput;
import com.aipanel.gateway.RosterParser;
import com.aipanel.gateway.RosterParticipant;
import com.aipanel.gateway.RosterStructureException;
import com.aipanel.model.Discussion;
import com.aipanel.model.Insight;
import com.aipanel.model.Participant;
import com.aipanel.model.Utterance;
import com.aipanel.repository.DiscussionRepository;
import com.aipanel.repository.InsightRepository;
import com.aipanel.repository.ParticipantRepository;
import com.aipanel.repository.UtteranceRepository;
import com.aipanel.service.debate.RuntimeCalls;
import com.aipanel.util.Colors;
import com.aipanel.util.Ids;
import com.aipanel.util.TopicNormalizer;
import com.aipanel.web.dto.DiscussionCreateRequest;
import com.aipanel.web.vo.DiscussionSnapshotVO;
import com.aipanel.web.vo.DiscussionSummaryVO;
import com.aipanel.web.vo.InsightVO;
import com.aipanel.web.vo.ParticipantVO;
import com.aipanel.web.vo.StartResultVO;
import com.aipanel.web.vo.UtteranceVO;

/** 房间草稿、快照、大厅列表与确认开场。模型调用与讨论执行都在事务之外。 */
@Service
public class DiscussionService {

    private static final Logger log = LoggerFactory.getLogger(DiscussionService.class);
    private static final int DEFAULT_EXPERT_COUNT = 4;
    private static final int MIN_TOPIC_LENGTH = 3;
    private static final int MAX_TOPIC_LENGTH = 120;

    private final DiscussionRepository discussions;
    private final ParticipantRepository participants;
    private final UtteranceRepository utterances;
    private final InsightRepository insights;
    private final ModelGateway gateway;
    private final RosterParser parser;
    private final RosterValidator validator;
    private final RosterQualityService quality;
    private final FaultInjector faultInjector;
    private final TransactionTemplate transactions;
    private final DiscussionEventPublisher publisher;
    private final DiscussionRunner runner;
    private final PanelProperties properties;
    private final ContentGuard contentGuard;
    private final RuntimeCalls runtimeCalls;

    public DiscussionService(DiscussionRepository discussions, ParticipantRepository participants,
            UtteranceRepository utterances, InsightRepository insights, ModelGateway gateway, RosterParser parser,
            RosterValidator validator, RosterQualityService quality, FaultInjector faultInjector,
            TransactionTemplate transactions, DiscussionEventPublisher publisher, DiscussionRunner runner,
            PanelProperties properties, ContentGuard contentGuard, RuntimeCalls runtimeCalls) {
        this.discussions = discussions;
        this.participants = participants;
        this.utterances = utterances;
        this.insights = insights;
        this.gateway = gateway;
        this.parser = parser;
        this.validator = validator;
        this.quality = quality;
        this.faultInjector = faultInjector;
        this.transactions = transactions;
        this.publisher = publisher;
        this.runner = runner;
        this.properties = properties;
        this.contentGuard = contentGuard;
        this.runtimeCalls = runtimeCalls;
    }

    /** 创建待确认草稿。阵容或事务失败时不留下任何房间、成员或事件记录。 */
    public DiscussionSnapshotVO create(DiscussionCreateRequest request) {
        String topic = TopicNormalizer.normalize(contentGuard.safeTopic(request == null ? null : request.getTopic()));
        int topicLength = TopicNormalizer.codePointLength(topic);
        if (topicLength < MIN_TOPIC_LENGTH || topicLength > MAX_TOPIC_LENGTH) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        int expertCount = resolveExpertCount(request);
        RosterOutput roster;
        try {
            roster = runtimeCalls.generateRoster(() -> generateRoster(topic, expertCount));
        } catch (ModelGatewayException failure) {
            throw toApiException(failure);
        }

        String roomId = Ids.uuid();
        Discussion discussion = new Discussion(roomId, topic, roster.discussionGoal(), expertCount, "DRAFT", 0,
                "PENDING", null, Ids.now(), null, null, null);
        List<Participant> members = toParticipants(roomId, roster);

        transactions.executeWithoutResult(status -> {
            discussions.insert(discussion);
            participants.insertAll(members);
            faultInjector.afterParticipantsInserted(roomId, members.size());
        });
        log.info("创建草稿 roomId={} goal={} experts={}", roomId, discussion.discussionGoal(), expertCount);
        return get(roomId);
    }

    /**
     * 确认开场。重复确认运行中的房间只返回当前状态；终态返回 409；容量不足返回 429 并保留草稿。
     */
    public synchronized StartResultVO start(String id) {
        Discussion room = discussions.findById(id).orElseThrow(() -> new ApiException(ApiErrorCode.B0002));
        if ("RUNNING".equals(room.status()) || "SUMMARIZING".equals(room.status())) {
            return new StartResultVO(id, room.status());
        }
        if ("COMPLETED".equals(room.status()) || "FAILED".equals(room.status())) {
            throw new ApiException(ApiErrorCode.B0003);
        }
        if (discussions.countActive() >= properties.orchestration().capacity()) {
            throw new ApiException(ApiErrorCode.B0004);
        }

        if (!runner.submit(id, () -> transactions.executeWithoutResult(status -> {
            if (!discussions.markRunning(id, Ids.now())) {
                throw new ApiException(ApiErrorCode.B0003);
            }
            publisher.publishStatus(id);
        }))) {
            throw new ApiException(ApiErrorCode.B0004);
        }
        log.info("确认开场 roomId={}", id);
        return new StartResultVO(id, "RUNNING");
    }

    /** 读取房间行，供事件流与管理台判断状态。 */
    public Discussion requireRoom(String id) {
        return discussions.findById(id).orElseThrow(() -> new ApiException(ApiErrorCode.B0002));
    }

    @Transactional(readOnly = true)
    public DiscussionSnapshotVO get(String id) {
        Discussion discussion = discussions.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.B0002));
        List<ParticipantVO> members = participants.findByDiscussionId(id).stream().map(this::toVO).toList();
        List<Utterance> transcript = utterances.findByDiscussionId(id);
        List<InsightVO> insightViews = insights.findByDiscussionId(id).stream().map(this::toVO).toList();
        String summary = transcript.stream()
                .filter(utterance -> "SUMMARY".equals(utterance.kind()))
                .map(Utterance::content)
                .findFirst()
                .orElse(null);
        int through = discussion.insightsThroughUtteranceSeq() == null ? 0 : discussion.insightsThroughUtteranceSeq();
        return new DiscussionSnapshotVO(discussion.id(), discussion.topic(), discussion.expertCount(),
                discussion.discussionGoal(), discussion.status(), discussion.startedAt(), discussion.endedAt(),
                ApiErrorCode.fromStoredCode(discussion.errorCode()), members,
                transcript.stream().map(this::toVO).toList(),
                discussion.insightStatus(), through, insightViews, summary, discussion.lastEventSeq());
    }

    public List<DiscussionSummaryVO> listActive() {
        return discussions.findActive().stream()
                .map(discussion -> new DiscussionSummaryVO(discussion.id(), discussion.topic(), discussion.status(),
                        discussion.expertCount(), discussion.startedAt()))
                .toList();
    }

    private int resolveExpertCount(DiscussionCreateRequest request) {
        if (request == null || !request.expertCountSpecified()) {
            return DEFAULT_EXPERT_COUNT;
        }
        Integer count = request.getExpertCount();
        if (count == null || count < 2 || count > RosterValidator.MAX_EXPERT_COUNT) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        return count;
    }

    /** 首次调用失败或校验不通过时，最多做一次 R08 补偿；内容拦截与缺配置不补偿。 */
    private RosterOutput generateRoster(String topic, int expertCount) {
        Attempt first = attempt(topic, expertCount, List.of());
        if (first.failure() != null) {
            if (!first.failure().repairable()) {
                throw toApiException(first.failure());
            }
            List<String> codes = first.failure().kind() == ModelGatewayException.Kind.FORMAT_INVALID
                    ? List.of(first.failure().getMessage()) : List.of();
            Attempt repair = attempt(topic, expertCount, codes);
            if (repair.failure() != null) {
                throw toApiException(repair.failure());
            }
            requireValid(repair.output(), expertCount);
            return repair.output();
        }
        List<String> issues = issues(first.output(), expertCount);
        if (issues.isEmpty()) {
            return first.output();
        }
        Attempt repair = attempt(topic, expertCount, issues);
        if (repair.failure() != null) {
            throw toApiException(repair.failure());
        }
        requireValid(repair.output(), expertCount);
        return repair.output();
    }

    private void requireValid(RosterOutput output, int expertCount) {
        if (!issues(output, expertCount).isEmpty()) {
            throw new ApiException(ApiErrorCode.B9001);
        }
    }

    private List<String> issues(RosterOutput output, int expertCount) {
        List<String> codes = new ArrayList<>(validator.validate(output, expertCount));
        codes.addAll(quality.validate(output));
        return List.copyOf(codes);
    }

    private Attempt attempt(String topic, int expertCount, List<String> validationCodes) {
        try {
            return new Attempt(parser.parse(gateway.generateRoster(topic, expertCount, validationCodes)), null);
        } catch (RosterStructureException error) {
            return new Attempt(null,
                    new ModelGatewayException(ModelGatewayException.Kind.FORMAT_INVALID, "SHAPE_INVALID"));
        } catch (ModelGatewayException error) {
            return new Attempt(null, error);
        }
    }

    private ApiException toApiException(ModelGatewayException failure) {
        ApiErrorCode code = switch (failure.kind()) {
            case CONFIG_MISSING -> ApiErrorCode.B4001;
            case TIMEOUT -> ApiErrorCode.B9002;
            default -> ApiErrorCode.B9001;
        };
        return new ApiException(code);
    }

    private List<Participant> toParticipants(String roomId, RosterOutput roster) {
        List<Participant> members = new ArrayList<>(roster.participants().size());
        int expertIndex = 0;
        for (RosterParticipant member : roster.participants()) {
            if ("HOST".equals(member.role())) {
                members.add(new Participant(roomId, "host", "HOST", member.displayName(), member.profession(),
                        member.title(), null, null, Colors.HOST, "IDLE", null));
            } else {
                members.add(new Participant(roomId, "e" + (++expertIndex), "EXPERT", member.displayName(),
                        member.profession(), member.title(), member.stance(), member.perspective(),
                        Colors.expertAt(expertIndex - 1), "IDLE", null));
            }
        }
        return members;
    }

    private ParticipantVO toVO(Participant member) {
        return new ParticipantVO(member.id(), member.role(), member.displayName(), member.profession(),
                member.title(), member.stance(), member.perspective(), member.colorKey(), member.stageStatus(),
                member.publicFocus());
    }

    private UtteranceVO toVO(Utterance utterance) {
        return new UtteranceVO(utterance.seq(), utterance.participantId(), utterance.kind(), utterance.content(),
                utterance.replyToUtteranceSeq(), utterance.createdAt());
    }

    private InsightVO toVO(Insight insight) {
        return new InsightVO(insight.id(), insight.kind(), insight.text(), insight.evidenceUtteranceSeqs());
    }

    private record Attempt(RosterOutput output, ModelGatewayException failure) {
    }
}
