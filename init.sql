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

-- 预设使用固定 slug，讨论目标和人数在种子中显式声明。
CREATE TEMP TABLE init_seed_preset (
    slug TEXT PRIMARY KEY,
    topic TEXT NOT NULL,
    discussion_goal TEXT NOT NULL,
    expert_count INTEGER NOT NULL
);
INSERT INTO init_seed_preset (slug, topic, discussion_goal, expert_count) VALUES
    ('ai-support-buy-build', '中小团队应优先自研还是采购 AI 客服？', 'EVALUATE', 4),
    ('city-curb-space', '市中心路边停车位应改为配送与无障碍上下客区吗？', 'EVALUATE', 4),
    ('hybrid-work-policy', '一家百人公司应如何设计混合办公规则？', 'PROPOSE', 4),
    ('museum-ai-guide', '博物馆应上线可追问的生成式 AI 导览吗？', 'EVALUATE', 4),
    ('school-ai-homework', '中学应允许学生用生成式 AI 完成家庭作业吗？', 'EVALUATE', 4);

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
    ('ai-support-buy-build', 'host', 'HOST', '顾言', '节目主持', '圆桌主持人', NULL, NULL, 'violet'),
    ('ai-support-buy-build', 'e1', 'EXPERT', '陈简', '成本管理', '成本控制负责人', '四周内交付时优先采购成熟方案', 'COST', 'blue'),
    ('ai-support-buy-build', 'e2', 'EXPERT', '林昭', '软件工程', '平台架构师', '规则频繁变更时维护自研编排', 'DELIVERY', 'green'),
    ('ai-support-buy-build', 'e3', 'EXPERT', '赵岚', '客服运营', '客户运营经理', '小流量核验服务效果再逐步拓展', 'EVIDENCE', 'amber'),
    ('ai-support-buy-build', 'e4', 'EXPERT', '方钰', '隐私工程', '隐私工程师', '优先明确数据留存和迁移权利', 'RISK', 'rose'),

    ('city-curb-space', 'host', 'HOST', '贾晨', '节目主持', '圆桌主持人', NULL, NULL, 'violet'),
    ('city-curb-space', 'e1', 'EXPERT', '冯远', '交通规划', '交通规划师', '按道路功能与通行时段分配路缘', 'GOVERNANCE', 'blue'),
    ('city-curb-space', 'e2', 'EXPERT', '唐立', '商户运营', '沿街商户代表', '保留便于经营交付的短时停车', 'COST', 'green'),
    ('city-curb-space', 'e3', 'EXPERT', '马会', '城市配送', '城市配送调度员', '先在配送高峰减少双排停车', 'DELIVERY', 'amber'),
    ('city-curb-space', 'e4', 'EXPERT', '秦宜', '无障碍出行', '无障碍出行顾问', '确保上下客通道全程保持连续', 'USER_IMPACT', 'rose'),

    ('hybrid-work-policy', 'host', 'HOST', '许明', '节目主持', '圆桌主持人', NULL, NULL, 'violet'),
    ('hybrid-work-policy', 'e1', 'EXPERT', '周弦', '人事管理', '人力资源负责人', '由团队自行安排主要协作工作日', 'GOVERNANCE', 'blue'),
    ('hybrid-work-policy', 'e2', 'EXPERT', '郑弋', '软件工程', '工程团队经理', '异步优先并减少统一到岗要求', 'DELIVERY', 'green'),
    ('hybrid-work-policy', 'e3', 'EXPERT', '姜平', '空间管理', '设施运营主管', '固定到岗日期便于空间规划安排', 'COST', 'amber'),
    ('hybrid-work-policy', 'e4', 'EXPERT', '苏晓', '无障碍研究', '无障碍项目顾问', '按办公可达性设置可申请的例外', 'USER_IMPACT', 'rose'),

    ('museum-ai-guide', 'host', 'HOST', '夏澄', '节目主持', '圆桌主持人', NULL, NULL, 'violet'),
    ('museum-ai-guide', 'e1', 'EXPERT', '任青', '展览策划', '展览策展人', '严格限定可引用的馆藏证据材料', 'EVIDENCE', 'blue'),
    ('museum-ai-guide', 'e2', 'EXPERT', '田朗', '互动产品', '互动产品经理', '先在少数热门展品开展单馆试点', 'DELIVERY', 'green'),
    ('museum-ai-guide', 'e3', 'EXPERT', '祝桦', '观众研究', '观众研究员', '保留人工入口及低技术门槛导览', 'USER_IMPACT', 'amber'),
    ('museum-ai-guide', 'e4', 'EXPERT', '罗澜', '内容编辑', '内容编辑主管', '可追溯材料充分后再开放追问', 'RISK', 'rose'),

    ('school-ai-homework', 'host', 'HOST', '梁青', '节目主持', '圆桌主持人', NULL, NULL, 'violet'),
    ('school-ai-homework', 'e1', 'EXPERT', '沈初', '语文教学', '语文教师', '保留独立写作与同伴反馈作为评估依据', 'EVIDENCE', 'blue'),
    ('school-ai-homework', 'e2', 'EXPERT', '白原', '数字素养', '数字素养教师', '明确引用规则后允许可追溯的辅助', 'GOVERNANCE', 'green'),
    ('school-ai-homework', 'e3', 'EXPERT', '叶秋', '教育研究', '教育研究员', '按作业难度开展小规模分层试点', 'DELIVERY', 'amber'),
    ('school-ai-homework', 'e4', 'EXPERT', '葛琴', '家校协作', '家校协作主管', '处理设备不平等之后再开放工具', 'USER_IMPACT', 'rose')
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
