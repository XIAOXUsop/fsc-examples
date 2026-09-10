package com.fsc.cases.mcp;

import com.fsc.cases.rag.LegalRag;
import com.fsc.cases.tool.AmlTools;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具目录的契约测试（离线，不需要传输层）。
 *
 * <p>校验的是「对外承诺的工具面」：工具名、描述、入参 schema 是否完整，
 * 以及处理函数是否真的复用了 {@link AmlTools} 的能力。
 */
class AmlMcpServerTest {

    private final AmlTools amlTools = new AmlTools(LegalRag.build());
    private final List<McpServerFeatures.SyncToolSpecification> specs = AmlMcpServer.tools(amlTools);

    @Test
    void exposesExactlyTheThreeAmlTools() {
        assertEquals(List.of("screen_sanctions", "search_regulations", "query_transactions"),
                specs.stream().map(spec -> spec.tool().name()).toList());
    }

    @Test
    void everyToolDeclaresDescriptionAndObjectSchema() {
        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            McpSchema.Tool tool = spec.tool();
            assertNotNull(tool.description(), tool.name() + " 缺少描述");
            assertFalse(tool.description().isBlank(), tool.name() + " 描述为空");

            Map<String, Object> schema = tool.inputSchema();
            assertNotNull(schema, tool.name() + " 缺少入参 schema");
            assertEquals("object", schema.get("type"), tool.name() + " schema 类型应为 object");
            assertFalse(propertiesOf(schema).isEmpty(), tool.name() + " 未声明任何入参");
        }
    }

    @Test
    void queryTransactionsSchemaDeclaresBothArguments() {
        Map<String, Object> properties = propertiesOf(toolNamed("query_transactions").inputSchema());

        // schema 声明的入参必须与处理函数实际读取的一致，否则契约与实现不符
        assertTrue(properties.containsKey("customerId"));
        assertTrue(properties.containsKey("months"));
    }

    @Test
    void screenSanctionsToolReportsHitAndMiss() {
        assertTrue(call("screen_sanctions", Map.of("customerName", "张三")).contains("命中"));
        assertTrue(call("screen_sanctions", Map.of("customerName", "李四")).contains("未命中"));
    }

    @Test
    void searchRegulationsToolReturnsEvidenceId() {
        String result = call("search_regulations", Map.of("query", "一级制裁名单"));

        assertTrue(result.contains("AML-"), "应返回带 evidenceId 的法规条文，实际：" + result);
    }

    @Test
    void queryTransactionsToolUsesRequestedMonths() {
        String result = call("query_transactions", Map.of("customerId", "C001", "months", 6));

        assertTrue(result.contains("6"), "应回显请求的月份数，实际：" + result);
    }

    @Test
    void queryTransactionsToolFallsBackToDefaultMonths() {
        String result = call("query_transactions", Map.of("customerId", "C001"));

        assertTrue(result.contains("3"), "未传 months 时应回落到默认值，实际：" + result);
    }

    // ---------- 辅助 ----------

    private McpSchema.Tool toolNamed(String name) {
        return specs.stream().map(McpServerFeatures.SyncToolSpecification::tool)
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到工具：" + name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        return properties instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 通过 MCP 的 callHandler 调用工具，验证的是客户端实际会走的那条路径 */
    private String call(String toolName, Map<String, Object> arguments) {
        McpServerFeatures.SyncToolSpecification spec = specs.stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到工具：" + toolName));

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(arguments)
                .build();
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(Boolean.TRUE.equals(result.isError()), "工具调用不应报错");
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .reduce("", String::concat);
    }
}
