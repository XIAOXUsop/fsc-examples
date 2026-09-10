package com.fsc.cases.tool;

import com.fsc.cases.rag.LegalRag;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;

import java.util.List;
import java.util.Set;

/**
 * AML 尽调 Agent 可调用的工具集。
 *
 * <p>全部为<b>只读</b>、确定性实现，离线可用：制裁名单与交易数据均为合成数据（非真实客户数据）。
 * 其中 {@code searchRegulations} 是法规 RAG 的出口，检索结果带 {@code evidenceId}，
 * 与 {@code GuardrailEvaluator} 的证据白名单校验形成闭环。
 */
public final class AmlTools {

    /** 合成制裁名单（演示用；真实系统应接入权威名单源） */
    private static final Set<String> SANCTIONED_NAMES = Set.of("张三", "王某", "刘某");

    private final LegalRag rag;

    public AmlTools(LegalRag rag) {
        this.rag = rag;
    }

    @Tool("按关键词检索 AML 法规条文，返回 evidenceId 与条文内容。风险评级结论必须引用检索到的 evidenceId。")
    public String searchRegulations(String query) {
        List<Content> contents = rag.contentRetriever().retrieve(Query.from(query));
        if (contents.isEmpty()) {
            return "未检索到相关法规";
        }
        StringBuilder sb = new StringBuilder();
        for (Content content : contents) {
            TextSegment segment = content.textSegment();
            sb.append('[').append(segment.metadata().getString("evidenceId")).append("] ")
              .append(segment.text().replace('\n', ' '))
              .append('\n');
        }
        return sb.toString().strip();
    }

    @Tool("制裁名单筛查：输入客户姓名，返回是否命中制裁名单及其等级。")
    public String screenSanctions(String customerName) {
        if (customerName == null || customerName.isBlank()) {
            return "未提供姓名，无法筛查";
        }
        return SANCTIONED_NAMES.contains(customerName)
                ? "命中一级制裁名单（按 AML-001 须评 HIGH 并转人工）"
                : "未命中制裁名单";
    }

    @Tool("查询客户近 N 个月的交易画像摘要（金额、笔数、时段、跨境特征）。客户 ID 为内部编号。")
    public String queryTransactions(String customerId, int months) {
        if (customerId == null || customerId.isBlank() || months <= 0) {
            return "参数无效：需要有效的客户 ID 与正整数月份";
        }
        // 合成画像：按客户 ID 稳定派生，保证可复现（非真实数据）
        int seed = Math.floorMod(customerId.hashCode(), 3);
        return switch (seed) {
            case 0 -> "近 %d 月：跨境汇款 2 笔，单笔等值 80 万元，发生于 01:00-03:00 夜间时段".formatted(months);
            case 1 -> "近 %d 月：同一网点等额现金存取 14 笔，每笔 5 万元，间隔约 2 小时".formatted(months);
            default -> "近 %d 月：交易 3 笔，均为工作日 14:00 前后，附报关单，无异常时段特征".formatted(months);
        };
    }
}
