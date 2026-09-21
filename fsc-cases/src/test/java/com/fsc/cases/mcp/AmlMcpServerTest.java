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

    /**
     * 「months 默认 3」必须是**契约上可达的**，而不是只写在描述里。
     *
     * <p>原先 {@code query_transactions} 的 schema 由 {@code schema(...)} 组装，
     * 而那个方法把声明的每个属性都塞进 {@code required}——于是描述里写着"默认 3"、
     * 契约上却是必填：一个照着 description 办事、不传 months 的合规客户端会被
     * 协议层的入参校验直接拒掉。**写着一个行为，契约上却禁止走到它**，
     * 而下面那条"不传 months 时回落到默认值"的测试是直接调处理函数，
     * 绕过了 schema 校验，所以一直是绿的。
     *
     * <p>这条测试钉的是"契约与描述一致"这件事本身：months 在 properties 里、
     * **不在** required 里，且 required 非空（customerId 仍然是必填的——
     * 顺带防住"把整个 required 删掉"这种过宽的修法）。
     */
    @Test
    void optionalMonthsIsActuallyOptionalInTheSchema() {
        Map<String, Object> schema = toolNamed("query_transactions").inputSchema();
        Map<String, Object> properties = propertiesOf(schema);

        assertTrue(properties.containsKey("months"), "months 应当声明在 properties 里");
        assertFalse(requiredOf(schema).contains("months"),
                "months 写在 required 里，描述里的「默认 " + 3 + "」在任何合规客户端上都不可达");
        assertTrue(requiredOf(schema).contains("customerId"), "customerId 仍应是必填");
        assertFalse(requiredOf(schema).isEmpty(), "required 不能为空——那等于所有入参都可选");
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(Map<String, Object> schema) {
        Object value = schema.get("required");
        assertTrue(value instanceof List, "schema 里的 required 应当是数组：" + value);
        return (List<String>) value;
    }

    /**
     * 制裁筛查的正负两侧要**互斥**。
     *
     * <p>这里原先两侧都断言 `contains("命中")`——而"未命中制裁名单"这句话**也含这两个字**，
     * 于是筛查器即使恒返回"未命中"（也就是完全失效、再也不报警），这条测试照样绿。
     * AML 场景里最危险的失效方向恰恰是漏报，而它是这条测试唯一的盲区。
     *
     * <p>实测（2026-09-22）：把 `screenSanctions` 改成恒返回"未命中制裁名单"，
     * 14 条测试全绿。`McpStdioProtocolTest` 只调 `search_regulations`，不覆盖这个工具，
     * 所以这是它在 CI 里的唯一断言。
     */
    @Test
    void screenSanctionsToolReportsHitAndMiss() {
        String hit = call("screen_sanctions", Map.of("customerName", "张三"));
        String miss = call("screen_sanctions", Map.of("customerName", "李四"));

        assertTrue(hit.contains("命中一级制裁名单"), "命中时要说清是哪种名单，实际：" + hit);
        assertTrue(hit.contains("AML-001"), "命中时要带出条文出处，实际：" + hit);
        assertFalse(hit.contains("未命中"), "命中不应同时出现「未命中」，实际：" + hit);

        assertTrue(miss.contains("未命中制裁名单"), "未命中时要明说，实际：" + miss);
        assertFalse(miss.contains("命中一级制裁名单"), "未命中不应出现命中，实际：" + miss);

        // 名单里的另一个名字也要能命中——只有一条正例时，
        // 把名单缩成单个名字也测不出来。
        assertTrue(call("screen_sanctions", Map.of("customerName", "刘某")).contains("命中一级制裁名单"),
                "名单里的多个名字都要能命中");

        // 空姓名是第三种情形，既不是命中也不是未命中
        assertTrue(call("screen_sanctions", Map.of("customerName", " ")).contains("无法筛查"),
                "空姓名应有独立提示");
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
