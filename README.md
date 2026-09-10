# FSC — FinReg Sample Cases

> 面向**金融合规（反洗钱）** 场景的 LangChain4j 示例集：**合规护栏 · 可评测 · 离线可跑**
>
> AML compliance LangChain4j examples with deterministic guardrails, reproducible offline evaluation, and mock-first design.

<div align="center">

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.5-6DB33F?logo=spring-boot&logoColor=white)
![LangChain4j](https://img.shields.io/badge/LangChain4j_1.20-4A9EFF)
![JUnit 5](https://img.shields.io/badge/JUnit_5-25A162?logo=junit5&logoColor=white)
![Offline](https://img.shields.io/badge/no_API_key_required-9cf)

</div>

## 这是什么

大多数 LangChain4j 官方示例止步于"把 PDF 喂给模型、打印答案"。但金融合规场景真正难的地方不在调模型，而在：

- 模型输出**不能直接采信** —— 必须有独立于大模型的确定性校验；
- 结论**必须可追溯** —— 每条评级都要引用法规证据；
- 效果**必须可度量** —— 不是"跑通了"，而是"准确率是多少"。

本仓库用一组**反洗钱尽职调查**案例，把这三件事完整演示出来。它是 [LangChain4j 官方示例](https://github.com/langchain4j/langchain4j-examples) 的**金融垂直场景补充**，而非简单复刻。

## 核心特性

| 特性 | 说明 |
|---|---|
| 🤖 **真实 Agent 流程** | 用 LangChain4j `AiServices` + `@Tool` + 结构化输出跑通 AML 尽调 Agent；模型输出映射为 `AgentAnalysis` 对象 |
| 📚 **法规 RAG** | `LegalRag` 把 AML 法规灌入向量库，经 `ContentRetriever` 检索，结论须引用检索到的 `evidenceId` |
| 🏦 **金融垂直语料** | 内置 AML 法规索引（`AML-001` ~ `AML-005`）与可疑交易案例集，取代官方示例的通用文档 |
| 🛡️ **确定性护栏** | `GuardrailEvaluator` 独立于模型做四类校验：证据引用白名单、条文语义强制、Prompt 注入扫描、无依据保守升级 |
| 📏 **可评测（Eval）** | `FinEvalTest` 对固定案例集双轨计分：**原始模型分 vs 护栏修正后分**，结果落盘 JSON 可 CI |
| 🔌 **Mock-first** | 无 API Key 时可离线全链路跑通，便于上手与 CI；配置 Key 后切换真实模型 |

### Agent 怎么工作

```
可疑交易描述
   → AiServices Agent（大模型）
       ├─ @Tool screenSanctions      制裁名单筛查
       ├─ @Tool queryTransactions    交易画像查询
       └─ @Tool searchRegulations    法规 RAG 检索（ContentRetriever）
   → 结构化输出 AgentAnalysis { riskLevel, rationale, evidenceId }
   → GuardrailEvaluator 确定性校验（证据白名单 / 条文强制 / 注入扫描）
   → 最终评级 + 是否转人工
```

> RAG 使用的 embedding 是**离线确定性哈希模型**（`HashingEmbeddingModel`），做的是词法近似匹配而非语义匹配 ——
> 这样保证零下载、可 CI。生产环境替换为真实语义 embedding 模型即可，RAG 装配代码无需改动。

### 护栏做了什么

模型给的结论会被 `GuardrailEvaluator` 再检查一遍：

1. **证据引用校验** —— 结论未引用 `LegalIndex` 中的合法 `evidenceId` → 判定为无依据，强制转人工
2. **条文语义强制** —— 命中一级制裁（`AML-001`）必须 `HIGH` + 转人工，模型给低评级会被强制升级
3. **Prompt 注入扫描** —— 输入命中注入特征（如 `ignore all previous instructions`）→ 转人工
4. **无依据兜底** —— 任何转人工的结论，若模型给的是低评级，一律保守升级为 `HIGH`

## 快速开始

需要 JDK 21 与 Maven。

```bash
# 全离线运行（Mock 模型 + 离线 RAG，无需任何 API Key、无需联网）
mvn -pl fsc-cases test

# 运行真实模型 Agent 冒烟测试（需自备 Key）
RUN_LIVE=true DEEPSEEK_API_KEY=xxx mvn -pl fsc-cases test -Dtest=AmlAgentLiveTest
```

运行结束后会在控制台打印双轨计分结果与逐条明细，`target/fsc-eval/` 下会写出 JSON 报告文件。

## 目录结构

```
fsc-examples/
├── pom.xml                    # 父 POM（Java 21 / Spring Boot 3.5 / LangChain4j 1.20）
└── fsc-cases/
    └── src/
        ├── main/java/com/fsc/cases/
        │   ├── RatingEngine.java              # 评级引擎：Mock 打分 + 真实模型 Agent 装配
        │   ├── agent/
        │   │   ├── AmlAgent.java              # AiServices 接口（@SystemMessage + 结构化返回）
        │   │   └── AmlAgentFactory.java       # 装配 ChatModel + 工具 + RAG
        │   ├── tool/
        │   │   └── AmlTools.java              # @Tool 工具集（制裁筛查 / 交易查询 / 法规检索）
        │   ├── rag/
        │   │   ├── LegalRag.java              # 法规向量库 + ContentRetriever 装配
        │   │   └── HashingEmbeddingModel.java # 离线确定性 embedding（零下载）
        │   ├── guardrail/
        │   │   └── GuardrailEvaluator.java    # 确定性合规护栏
        │   ├── data/
        │   │   ├── LegalIndex.java            # AML 法规索引（evidenceId 白名单）
        │   │   └── CaseDataset.java           # 合成案例集（含困难负例、注入样例）
        │   └── model/
        │       ├── AgentAnalysis.java         # 结构化输出（不带客户身份信息）
        │       └── RiskLevel.java             # 风险等级闭集（防幻觉产出未定义等级）
        └── test/java/com/fsc/cases/
            ├── FinEvalTest.java               # 双轨评测（离线）
            ├── LegalRagTest.java              # RAG 检索测试（离线）
            └── agent/AmlAgentLiveTest.java    # 真实模型冒烟（默认跳过）
```

## 与原仓库的差异

| | LangChain4j 官方示例 | 本仓库 |
|---|---|---|
| 语料 | 通用文档（如 "关于 Lance 的 PDF"） | AML 法规 + 可疑交易案例 |
| 输出校验 | 无 | 独立护栏，四类确定性规则 |
| 评测 | 无 | 双轨计分 + 落盘报告 |
| 上手门槛 | 需真实 API Key | Mock-first，离线可跑 |

## 后续方向

- [ ] 接入 LangChain4j RAG（向量检索法规条文）替代内置常量索引
- [ ] 真实模型的 Agent 多轮工具调用示例
- [ ] 评测数据集扩充与领域专家复核标签

## License

[MIT](LICENSE) © 2026 XIAOXUsop
