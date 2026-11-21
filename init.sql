-- AI Panel Studio SQLite 初始化脚本
-- 用于空库初始化，可对符合当前结构的数据库重复执行。
-- 旧版数据库先执行 migrations/002-host-stance.sql。
-- 命令行用法：sqlite3 -bail roundtable.db ".read init.sql"

PRAGMA foreign_keys = ON;

-- 表结构和预设在同一事务内初始化。
BEGIN IMMEDIATE;

CREATE TABLE IF NOT EXISTS discussion (
    id TEXT NOT NULL PRIMARY KEY,
    topic TEXT NOT NULL CHECK (length(trim(topic)) BETWEEN 3 AND 120),
    discussion_goal TEXT NOT NULL CHECK (discussion_goal IN ('EVALUATE','PROPOSE','SOLVE','EXPLORE')),
    expert_count INTEGER NOT NULL CHECK (typeof(expert_count) = 'integer' AND expert_count BETWEEN 2 AND 6),
    status TEXT NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','RUNNING','SUMMARIZING','COMPLETED','FAILED')),
    last_event_seq INTEGER NOT NULL DEFAULT 0
        CHECK (typeof(last_event_seq) = 'integer' AND last_event_seq >= 0),
    insight_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (insight_status IN ('PENDING','FRESH','STALE')),
    insights_through_utterance_seq INTEGER,
    created_at TEXT NOT NULL,
    started_at TEXT,
    ended_at TEXT,
    error_code TEXT,
    CHECK (insights_through_utterance_seq IS NULL OR
        (typeof(insights_through_utterance_seq) = 'integer' AND insights_through_utterance_seq > 0)),
    CHECK ((insight_status = 'PENDING' AND insights_through_utterance_seq IS NULL) OR
           (insight_status = 'FRESH' AND insights_through_utterance_seq IS NOT NULL) OR
           insight_status = 'STALE'),
    CHECK (
        (status = 'DRAFT' AND started_at IS NULL AND ended_at IS NULL AND error_code IS NULL) OR
        (status IN ('RUNNING','SUMMARIZING') AND started_at IS NOT NULL
            AND ended_at IS NULL AND error_code IS NULL) OR
        (status = 'COMPLETED' AND started_at IS NOT NULL AND ended_at IS NOT NULL AND error_code IS NULL) OR
        (status = 'FAILED' AND started_at IS NOT NULL AND ended_at IS NOT NULL
            AND error_code IS NOT NULL AND length(trim(error_code)) > 0)
    ),
    FOREIGN KEY (id, insights_through_utterance_seq) REFERENCES utterance (discussion_id, seq)
);
CREATE INDEX IF NOT EXISTS ix_discussion_status_started ON discussion (status, started_at);

CREATE TABLE IF NOT EXISTS participant (
    discussion_id TEXT NOT NULL REFERENCES discussion (id),
    id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('HOST','EXPERT')),
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) BETWEEN 2 AND 20),
    profession TEXT NOT NULL CHECK (length(trim(profession)) BETWEEN 2 AND 32),
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 2 AND 40),
    stance TEXT,
    perspective TEXT,
    color_key TEXT NOT NULL CHECK (length(trim(color_key)) > 0),
    stage_status TEXT NOT NULL DEFAULT 'IDLE' CHECK (stage_status IN ('IDLE','PREPARING','SPEAKING')),
    public_focus TEXT,
    PRIMARY KEY (discussion_id, id),
    UNIQUE (discussion_id, color_key),
    CHECK (
        (role = 'HOST' AND stance IS NULL AND perspective IS NULL) OR
        (role = 'EXPERT' AND stance IS NOT NULL AND length(trim(stance)) BETWEEN 8 AND 100 AND
            perspective IS NOT NULL AND
            perspective IN ('EVIDENCE','DELIVERY','COST','RISK','USER_IMPACT','GOVERNANCE'))
    ),
    CHECK (stage_status <> 'IDLE' OR public_focus IS NULL)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_participant_host ON participant (discussion_id) WHERE role = 'HOST';
CREATE UNIQUE INDEX IF NOT EXISTS ux_participant_perspective ON participant (discussion_id, perspective) WHERE role = 'EXPERT';

CREATE TABLE IF NOT EXISTS utterance (
    discussion_id TEXT NOT NULL,
    seq INTEGER NOT NULL CHECK (typeof(seq) = 'integer' AND seq > 0),
    participant_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('OPENING','EXPERT','FOLLOW_UP','BRIDGE','SUMMARY')),
    content TEXT NOT NULL CHECK (length(trim(content)) BETWEEN 2 AND
        CASE WHEN kind = 'SUMMARY' THEN 200 ELSE 120 END),
    reply_to_utterance_seq INTEGER,
    created_at TEXT NOT NULL,
    PRIMARY KEY (discussion_id, seq),
    FOREIGN KEY (discussion_id, participant_id) REFERENCES participant (discussion_id, id),
    FOREIGN KEY (discussion_id, reply_to_utterance_seq) REFERENCES utterance (discussion_id, seq),
    CHECK (reply_to_utterance_seq IS NULL OR
        (typeof(reply_to_utterance_seq) = 'integer' AND reply_to_utterance_seq > 0 AND reply_to_utterance_seq < seq)),
    CHECK ((kind <> 'BRIDGE' OR reply_to_utterance_seq IS NOT NULL) AND
           (kind NOT IN ('OPENING','SUMMARY') OR reply_to_utterance_seq IS NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_utterance_summary ON utterance (discussion_id) WHERE kind = 'SUMMARY';

CREATE TABLE IF NOT EXISTS insight (
    discussion_id TEXT NOT NULL REFERENCES discussion (id),
    id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('CONSENSUS','DISAGREEMENT')),
    text TEXT NOT NULL CHECK (length(trim(text)) BETWEEN 4 AND 80),
    updated_at TEXT NOT NULL,
    PRIMARY KEY (discussion_id, id)
);

CREATE TABLE IF NOT EXISTS insight_evidence (
    discussion_id TEXT NOT NULL,
    insight_id TEXT NOT NULL,
    utterance_seq INTEGER NOT NULL,
    PRIMARY KEY (discussion_id, insight_id, utterance_seq),
    FOREIGN KEY (discussion_id, insight_id) REFERENCES insight (discussion_id, id) ON DELETE CASCADE,
    FOREIGN KEY (discussion_id, utterance_seq) REFERENCES utterance (discussion_id, seq)
);

CREATE TABLE IF NOT EXISTS discussion_event (
    discussion_id TEXT NOT NULL REFERENCES discussion (id),
    seq INTEGER NOT NULL CHECK (typeof(seq) = 'integer' AND seq > 0),
    type TEXT NOT NULL CHECK (type IN ('discussion.status','participant.status','utterance.added','insight.updated')),
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (discussion_id, seq)
);

CREATE TABLE IF NOT EXISTS preset (
    slug TEXT NOT NULL PRIMARY KEY,
    topic TEXT NOT NULL CHECK (length(trim(topic)) BETWEEN 3 AND 120),
    discussion_goal TEXT NOT NULL CHECK (discussion_goal IN ('EVALUATE','PROPOSE','SOLVE','EXPLORE')),
    expert_count INTEGER NOT NULL CHECK (typeof(expert_count) = 'integer' AND expert_count BETWEEN 2 AND 6)
);

CREATE TABLE IF NOT EXISTS preset_participant (
    preset_slug TEXT NOT NULL REFERENCES preset (slug),
    id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('HOST','EXPERT')),
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) BETWEEN 2 AND 20),
    profession TEXT NOT NULL CHECK (length(trim(profession)) BETWEEN 2 AND 32),
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 2 AND 40),
    stance TEXT,
    perspective TEXT,
    color_key TEXT NOT NULL CHECK (length(trim(color_key)) > 0),
    PRIMARY KEY (preset_slug, id),
    UNIQUE (preset_slug, color_key),
    CHECK (
        (role = 'HOST' AND stance IS NULL AND perspective IS NULL) OR
        (role = 'EXPERT' AND stance IS NOT NULL AND length(trim(stance)) BETWEEN 8 AND 100 AND
            perspective IS NOT NULL AND
            perspective IN ('EVIDENCE','DELIVERY','COST','RISK','USER_IMPACT','GOVERNANCE'))
    )
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_preset_host ON preset_participant (preset_slug) WHERE role = 'HOST';
CREATE UNIQUE INDEX IF NOT EXISTS ux_preset_perspective ON preset_participant (preset_slug, perspective) WHERE role = 'EXPERT';

-- 替换首版内置预设。以稳定标识定位旧种子，先删除从属成员以满足外键约束。
CREATE TEMP TABLE init_retired_preset (slug TEXT PRIMARY KEY);
INSERT INTO init_retired_preset (slug) VALUES
    ('ai-support-buy-build'),
    ('city-curb-space'),
    ('hybrid-work-policy'),
    ('museum-ai-guide'),
    ('school-ai-homework');
DELETE FROM preset_participant WHERE preset_slug IN (SELECT slug FROM init_retired_preset);
DELETE FROM preset WHERE slug IN (SELECT slug FROM init_retired_preset);
DROP TABLE init_retired_preset;

-- 预设使用固定 slug，讨论目标和人数在种子中显式声明。
CREATE TEMP TABLE init_seed_preset (
    slug TEXT PRIMARY KEY,
    topic TEXT NOT NULL,
    discussion_goal TEXT NOT NULL,
    expert_count INTEGER NOT NULL
);
INSERT INTO init_seed_preset (slug, topic, discussion_goal, expert_count) VALUES
    ('ai-graduate-careers', 'AI 对应届计算机毕业生的影响有多大？', 'EXPLORE', 4),
    ('bus-demand-shuttle', '近郊产业园如何试点预约接驳，让夜班通勤更可靠？', 'PROPOSE', 4),
    ('clinic-message-reminders', '社区诊所应把复诊提醒默认改为微信消息吗？', 'EVALUATE', 4),
    ('grocery-pickup-delays', '生鲜门店上线自提预约后，晚高峰等待为何反而变长？', 'SOLVE', 4),
    ('office-retrofit-heat', '老旧办公楼降温，应优先换空调还是先做外遮阳？', 'EVALUATE', 4),
    ('rental-repair-disputes', '租赁社区如何改进报修流程，减少超时和押金纠纷？', 'PROPOSE', 4);

-- 记录本次新增的预设，仅为这些预设写入整组成员。
CREATE TEMP TABLE init_new_preset AS
SELECT seed.slug, seed.topic, seed.discussion_goal, seed.expert_count
FROM init_seed_preset AS seed
WHERE NOT EXISTS (SELECT 1 FROM preset WHERE slug = seed.slug);

INSERT INTO preset (slug, topic, discussion_goal, expert_count)
SELECT slug, topic, discussion_goal, expert_count
FROM init_new_preset;

WITH seed_member (preset_slug, id, role, display_name, profession, title, stance, perspective, color_key) AS (
    VALUES
    ('ai-graduate-careers', 'host', 'HOST', '闻知夏', '议题访谈', '圆桌主持人', NULL, NULL, 'violet'),
    ('ai-graduate-careers', 'e1', 'EXPERT', '陆行之', '软件研发校招', '研发校招负责人', '如果外包与正式岗位都缩招，我会先看招聘预算；只有相同预算下例行编码岗名额降得更多，才倾向于认为 AI 替代了部分新人任务。', 'GOVERNANCE', 'blue'),
    ('ai-graduate-careers', 'e2', 'EXPERT', '许闻川', '研发工程', '初级开发带教工程师', '我预期 AI 能让新人更快完成有清晰验收标准的小改动；若返工耗时抵消初稿节省，我会下调对实际交付收益的判断。', 'DELIVERY', 'green'),
    ('ai-graduate-careers', 'e3', 'EXPERT', '方宁', '高校就业服务', '计算机专业毕业去向跟踪教师', '我倾向于认为 AI 辅助作品先改善无实习学生的面试机会；若同类岗位中首份录用同样提前，收益就可能延伸到初筛之后。', 'USER_IMPACT', 'amber'),
    ('ai-graduate-careers', 'e4', 'EXPERT', '梁若衡', '劳动经济研究', '技术变迁与就业研究员', '我会用低暴露岗位作对照估计影响；若高低暴露岗位在普及前就有不同的招聘趋势，这个差值不能直接解释为 AI 的影响。', 'EVIDENCE', 'rose'),

    ('bus-demand-shuttle', 'host', 'HOST', '苏迢', '议题访谈', '圆桌主持人', NULL, NULL, 'violet'),
    ('bus-demand-shuttle', 'e1', 'EXPERT', '沈芮', '公交运营', '园区接驳线路调度主管', '我主张保留下班高峰的固定班次，把预约车放到尾班后；若低谷预约能稳定合乘并让等车时间更短，再扩大覆盖。', 'DELIVERY', 'blue'),
    ('bus-demand-shuttle', 'e2', 'EXPERT', '龚可', '工业园区用工服务', '园区工会通勤协调员', '我主张保留电话预约和临时登车名额，优先帮助排班随时变化的人；如果这些名额长期闲置，再缩减预留。', 'USER_IMPACT', 'green'),
    ('bus-demand-shuttle', 'e3', 'EXPERT', '贺嘉树', '公共交通财务', '公交成本与排班分析员', '我倾向于先承诺到主要换乘站的接驳，按每次完成的夜班换乘算成本；若远端站点能减少大量单独接送，可纳入试点。', 'COST', 'amber'),
    ('bus-demand-shuttle', 'e4', 'EXPERT', '彭逸', '交通服务监管', '城市公交服务质量主管', '我主张先明确错过末班车的兜底责任，再扩大预约服务；如果试点能记录并处理漏接投诉，可放宽固定站点限制。', 'GOVERNANCE', 'rose'),

    ('clinic-message-reminders', 'host', 'HOST', '程蔓', '议题访谈', '圆桌主持人', NULL, NULL, 'violet'),
    ('clinic-message-reminders', 'e1', 'EXPERT', '贾映雪', '基层医疗运营', '社区卫生服务中心预约主管', '我倾向对已验证绑定的患者默认用微信提醒；若未读比例升高且爽约率没有下降，就按联系习惯分流渠道。', 'DELIVERY', 'blue'),
    ('clinic-message-reminders', 'e2', 'EXPERT', '陈言秋', '基层卫生服务研究', '复诊依从性研究员', '只有同年龄、同随访频率患者的实际复诊率提升，我才支持替换默认渠道；消息成功送达只能说明渠道可用。', 'EVIDENCE', 'green'),
    ('clinic-message-reminders', 'e3', 'EXPERT', '谭晓岚', '老年数字服务', '老年就医协助项目负责人', '我主张由患者自己选择，特别是使用家属代管手机的人；如果电话随访能及时覆盖未读患者，我可以接受微信作为默认。', 'USER_IMPACT', 'amber'),
    ('clinic-message-reminders', 'e4', 'EXPERT', '严明舟', '医疗隐私治理', '基层医疗数据保护负责人', '只有绑定可撤销且锁屏通知不显示诊疗信息，我才支持默认微信；共用手机的告知与授权若无法核验，仍应沿用已确认的渠道。', 'RISK', 'rose'),

    ('grocery-pickup-delays', 'host', 'HOST', '孟澄', '议题访谈', '圆桌主持人', NULL, NULL, 'violet'),
    ('grocery-pickup-delays', 'e1', 'EXPERT', '韩池', '生鲜门店运营', '门店拣货班组长', '我怀疑预约单在高峰前集中下单，拣货赶不上取货；若到店前备齐的订单同样排长队，拣货就不是首要瓶颈。', 'DELIVERY', 'blue'),
    ('grocery-pickup-delays', 'e2', 'EXPERT', '樊岑', '零售履约分析', '门店履约数据分析师', '我会核对上线前后等待起止点是否一致；若用相同到店和交付时间重算后差异仍在，再按门店和时段查原因。', 'EVIDENCE', 'green'),
    ('grocery-pickup-delays', 'e3', 'EXPERT', '庄雨亭', '零售服务设计', '门店取货体验研究员', '我怀疑顾客到店后找不到专用取货入口，导致二次排队；若入口清晰的门店也出现同等延迟，应优先查柜台交接。', 'USER_IMPACT', 'amber'),
    ('grocery-pickup-delays', 'e4', 'EXPERT', '丁叙', '冷链质量管理', '门店冷链品控主管', '我会先排查冷藏品临时补拣是否堵住交接；若常温单同幅变慢，不能用冷链异常解释全部等待增长。', 'RISK', 'rose'),

    ('office-retrofit-heat', 'host', 'HOST', '常栖', '议题访谈', '圆桌主持人', NULL, NULL, 'violet'),
    ('office-retrofit-heat', 'e1', 'EXPERT', '纪文翊', '建筑节能', '既有建筑能耗诊断工程师', '若西晒区负荷集中在午后，我倾向先装外遮阳；如果夜间和背阴区也普遍过热，应先查空调制冷能力。', 'EVIDENCE', 'blue'),
    ('office-retrofit-heat', 'e2', 'EXPERT', '顾晴', '楼宇运维', '商业楼宇暖通运维主管', '若故障主要来自老机组频繁停机，我支持先更换故障机组；若保养后制冷能力达标，就先处理窗面得热。', 'DELIVERY', 'green'),
    ('office-retrofit-heat', 'e3', 'EXPERT', '卢清越', '物业资产管理', '办公楼改造造价负责人', '我支持以全周期成本比较两条路径：遮阳若要长时间脚手架和外墙审批，局部换机可能更划算；低成本可拆方案会改变排序。', 'COST', 'amber'),
    ('office-retrofit-heat', 'e4', 'EXPERT', '乔予安', '室内环境设计', '办公舒适度评估师', '我优先考虑工位体感与日照眩光，倾向在窗边试装遮阳；若遮阳后相邻区域温差和投诉没有下降，优先调整空调分区。', 'USER_IMPACT', 'rose'),

    ('rental-repair-disputes', 'host', 'HOST', '夏临', '议题访谈', '圆桌主持人', NULL, NULL, 'violet'),
    ('rental-repair-disputes', 'e1', 'EXPERT', '季雪宁', '住房租赁服务', '长租社区管家主管', '我主张受理时即区分漏水断电与普通维修，并给租客明确上门时段；若分级后普通故障积压，需要单独安排小修班次。', 'DELIVERY', 'blue'),
    ('rental-repair-disputes', 'e2', 'EXPERT', '乔晟', '住宅维修运营', '小区维修派工负责人', '我倾向把批量上门用于低风险小修；若同类问题重复返修率上升，先独立处理易误诊的故障，再决定是否合并。', 'COST', 'green'),
    ('rental-repair-disputes', 'e3', 'EXPERT', '袁珂', '消费权益调解', '住房租赁纠纷调解员', '我主张报修时记录设备初始状态和正常损耗，扣押金时逐项出示依据；若现场照片不能说明责任，安排双方核对。', 'GOVERNANCE', 'amber'),
    ('rental-repair-disputes', 'e4', 'EXPERT', '韩舒', '社区租客服务', '租客服务站协调员', '我优先提供可重新预约的晚间时段和进度通知；若爽约集中在日间第三方维修商，先改善上门承诺再增加通知。', 'USER_IMPACT', 'rose')
)
INSERT INTO preset_participant (preset_slug, id, role, display_name, profession, title, stance, perspective, color_key)
SELECT member.preset_slug, member.id, member.role, member.display_name, member.profession,
       member.title, member.stance, member.perspective, member.color_key
FROM seed_member AS member
JOIN init_new_preset AS added ON added.slug = member.preset_slug;

-- 校验种子、外键和立场字段版本，避免旧版表被 CREATE IF NOT EXISTS 跳过后继续使用。
CREATE TEMP TABLE init_validation (
    valid INTEGER NOT NULL CONSTRAINT init_schema_seed_and_foreign_keys_valid CHECK (valid = 1)
);
INSERT OR ROLLBACK INTO init_validation (valid)
SELECT 0
WHERE EXISTS (
    SELECT seed.slug
    FROM init_seed_preset AS seed
    LEFT JOIN preset AS p ON p.slug = seed.slug
    LEFT JOIN preset_participant AS member ON member.preset_slug = seed.slug
    GROUP BY seed.slug, seed.discussion_goal, seed.expert_count, p.discussion_goal, p.expert_count
    HAVING p.discussion_goal IS NOT seed.discussion_goal
        OR p.expert_count IS NOT seed.expert_count
        OR COUNT(CASE WHEN member.role = 'HOST' THEN 1 END) <> 1
        OR COUNT(CASE WHEN member.role = 'EXPERT' THEN 1 END) <> seed.expert_count
    )
    OR EXISTS (SELECT 1 FROM pragma_foreign_key_check)
    OR EXISTS (SELECT 1 FROM pragma_table_info('participant') WHERE name = 'stance' AND "notnull" = 1)
    OR EXISTS (SELECT 1 FROM pragma_table_info('preset_participant') WHERE name = 'stance' AND "notnull" = 1);

DROP TABLE init_validation;
DROP TABLE init_new_preset;
DROP TABLE init_seed_preset;
COMMIT;
