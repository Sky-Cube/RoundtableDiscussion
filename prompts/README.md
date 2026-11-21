# 应用运行时 Prompt

运行时模板版本为 `v2.8`。默认末阶段完成常规讨论后，专家可围绕未决争点继续回应，直至没有合格报名或预算触及收尾线。本版按岗位可观察的材料组建阵容，新增探索议题的影响程度阶段，并让专家通过查证技能核验公开主张。[运行时规格](./runtime.md) · [模型输出契约](../docs/runtime-contract.schema.json)

## 模板装配

系统消息使用下表指向的模板正文。议题、角色卡、实录、检索结果和修正编号放在独立的 JSON 数据消息中。

| 任务 | 系统消息装配顺序 |
| --- | --- |
| R01 阵容 | 公共约束 → 阵容任务 → [主持建角](./roles/host-profile.md) → [专家建角](./roles/expert-profile.md) → [议题路由](./route.md) → 四类策略各自的 `[R01]` 段（段前标明类型）→ `rosterOutput` Schema |
| R02–R07 | 公共约束 → 主持职责（R05–R07）→ 对应任务模板 → 已保存议题类型的对应 `[Rxx]` 段 → 该类型的阶段段落（有对应项时）→ 当前任务 Schema |
| R08 修正 | 沿用原调用的模板版本、类型策略、阶段、数据快照和 Schema，在 Schema 前追加修正模板。数据消息追加服务端生成的 `validationCodes` |

公共约束来自 [common.md](./common.md)。主持职责来自 [host.md](./roles/host.md)，主持中立规则优先于议题策略和可定制措辞。阶段段落只描述当前阶段的关注点，不改变任务角色：专家据此贡献，R04 据此排序提炼重点，主持据此提问，均不得据此新增业务内容。R01 一次完成分类及生成阵容。后续任务按房间保存的目标加载单一策略。类型和阶段由服务端固定映射，模板校验覆盖全部内置类型、任务段与配置中列出的阶段段落。房间使用同一套模板版本。

R01 的允许视角以输入字段 `allowedPerspectives` 传入；R05 的 `perspectives` 是已选专家的视角值，两者不要混用。

启动时后端读取 [流程模板](./workflow.json)。每个议题的数组顺序决定阶段执行顺序，阶段 ID 对应 `goals/` 中的同名段落。调度和 Prompt 装配共用这份配置。配置 `PANEL_AI_TEMPLATE_DIRECTORY` 可从指定目录加载 Markdown 和 `workflow.json`，该目录未提供流程文件时使用包内流程。修改后重启后端生效。

| 议题类型 | 策略模板 |
| --- | --- |
| `EVALUATE` 评估与选择 | [evaluate.md](./goals/evaluate.md) |
| `PROPOSE` 方案设计 | [propose.md](./goals/propose.md) |
| `SOLVE` 问题排查 | [solve.md](./goals/solve.md) |
| `EXPLORE` 开放探索 | [explore.md](./goals/explore.md) |

| 任务 | 指令模板 |
| --- | --- |
| R01 阵容 | [roster.md](./roles/roster.md) |
| R02 专家报名 | [expert-bid.md](./roles/expert-bid.md) |
| R03 专家发言 | [expert-speech.md](./roles/expert-speech.md) |
| R04 结论提炼 | [insights.md](./tasks/insights.md) |
| R05 主持开场 | [host-opening.md](./roles/host-opening.md) |
| R06 主持介入 | [host-intervention.md](./roles/host-intervention.md) |
| R07 主持总结 | [host-summary.md](./roles/host-summary.md) |
| R08 契约修正 | [repair.md](./tasks/repair.md) |

## 自定义角色

修改 `roles/host-profile.md` 或 `roles/expert-profile.md` 可调整生成角色卡的要求。修改报名、发言、开场、介入或总结模板可调整对应阶段的表达。使用独立文件替换模板时，由服务端模板配置把文件映射到既有模板键，例如把 `roles/host-opening.md` 指向自定义文件；新增任务或角色需要同时扩展服务端模板清单和装配顺序。采用 UTF-8，变更后更新模板版本。用 R01 阵容样例和受影响任务的验收用例检查字段、主持中立与专家视角差异。新增角色模板可沿用现有 `HOST`、`EXPERT` 及专家视角枚举。 [各任务样例与校验](./runtime.md)

## 专家查证

普通报名可直接附上一句可播台词。只有依赖复杂事实或前沿研究的贡献才申请后台查证。专家保持待机，查证结束后再参与裁决，其他就绪专家可先发言。后台时限默认 12 秒，可通过 `PANEL_AI_EVIDENCE_TIMEOUT_MILLIS` 调整。[证据检索技能](../.agents/skills/insight-evidence-retrieval/SKILL.md) · [处理边界](./runtime.md#发言前证据检索)

真实模型生成的阵容、讨论实录检查和检索结果见[毕业生议题验证记录](../docs/evidence/运行时提示词-v2.6.md)。
