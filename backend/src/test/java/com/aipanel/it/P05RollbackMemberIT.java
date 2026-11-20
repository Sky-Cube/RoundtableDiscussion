package com.aipanel.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.config.FaultInjector;
import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;
import com.aipanel.support.TestFaultInjector;

import static org.assertj.core.api.Assertions.assertThat;

/** P05-ROLLBACK-MEMBER：事务内故障时房间、成员与事件全部回滚，不留半条记录。 */
class P05RollbackMemberIT extends AbstractPanelIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("成员写入后抛出异常，三张表均无新增")
    void rollsBackAfterParticipantInsert() throws Exception {
        String topic = "回滚用例-成员";
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid(4)));
        faultInjector.mode(TestFaultInjector.Mode.PARTICIPANTS);
        int discussions = count("SELECT COUNT(*) FROM discussion");
        int participants = count("SELECT COUNT(*) FROM participant");
        int events = count("SELECT COUNT(*) FROM discussion_event");

        MvcResult result = createDraft(createBody(topic));

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(500);
        assertThat(errorOf(result).get("data").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
        assertThat(count("SELECT COUNT(*) FROM participant")).isEqualTo(participants);
        assertThat(count("SELECT COUNT(*) FROM discussion_event")).isEqualTo(events);
        assertThat(count("SELECT COUNT(*) FROM discussion WHERE topic = ?", topic)).isZero();
    }

    @Test
    @DisplayName("事务内先写合成事件再抛出，事件与业务记录一起回滚")
    void rollsBackSyntheticEvent() throws Exception {
        String topic = "回滚用例-事件";
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid(4)));
        faultInjector.mode(TestFaultInjector.Mode.PARTICIPANTS_WITH_EVENT);
        int discussions = count("SELECT COUNT(*) FROM discussion");
        int participants = count("SELECT COUNT(*) FROM participant");
        int events = count("SELECT COUNT(*) FROM discussion_event");

        MvcResult result = createDraft(createBody(topic));

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
        assertThat(count("SELECT COUNT(*) FROM participant")).isEqualTo(participants);
        assertThat(count("SELECT COUNT(*) FROM discussion_event")).isEqualTo(events);
    }

    @Test
    @DisplayName("正常草稿不写任何事件，lastEventSeq 为 0")
    void healthyDraftWritesNoEvent() throws Exception {
        String topic = "回滚用例-正常";
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid(4)));

        MvcResult result = createDraft(createBody(topic));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String roomId = dataOf(result).get("id").asText();
        assertThat(count("SELECT COUNT(*) FROM discussion_event WHERE discussion_id = ?", roomId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT last_event_seq FROM discussion WHERE id = ?", Integer.class,
                roomId)).isZero();
    }

    @Test
    @DisplayName("测试 profile 只装配测试故障注入器")
    void usesTestOnlyFaultInjector() {
        assertThat(applicationContext.getBeansOfType(FaultInjector.class)).hasSize(1);
        assertThat(applicationContext.getBean(FaultInjector.class)).isInstanceOf(TestFaultInjector.class);
    }
}