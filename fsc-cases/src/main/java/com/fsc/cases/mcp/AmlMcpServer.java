package com.fsc.cases.mcp;

import com.fsc.cases.tool.AmlTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 把本项目的 AML 能力通过 <b>MCP（Model Context Protocol）</b> 暴露为标准工具面。
 *
 * <p>MCP 是模型与外部工具／上下文之间的开放协议。暴露之后，任何支持 MCP 的客户端
 * （Claude Desktop、各类 IDE 助手等）都能直接调用这里的能力，<b>无需为本项目单独写适配</b>。
 *
 * <p>关键点：这里暴露的工具与 {@link AmlTools} 中标注 {@code @Tool} 的方法是
 * <b>同一份实现</b>——LangChain4j Agent 走的路径与 MCP 客户端走的路径完全一致，
 * 不存在"演示用一套、真用另一套"的两套逻辑。
 *
 * <p>另需说明：MCP 只负责把能力标准化输出，<b>不承担输出校验</b>。
 * 风险结论仍须经过 {@code GuardrailEvaluator} 的确定性护栏，这一点不因接入 MCP 而改变。
 */
public final class AmlMcpServer {

    public static final String SERVER_NAME = "fsc-aml-compliance";
    public static final String SERVER_VERSION = "0.2.0";

    /** 未指定时默认查询近 3 个月 */
    private static final int DEFAULT_MONTHS = 3;

    private static final String INSTRUCTIONS = """
            本服务提供反洗钱（AML）合规检查能力：制裁名单筛查、法规条文检索、客户交易画像。

            - 三个工具均为只读，不会修改任何数据；
            - 制裁名单与交易数据均为合成数据，仅供演示与联调，不代表真实客户信息；
            - 工具返回的法规条文带 evidenceId，风险结论应引用该编号以便追溯。
            """;

    private AmlMcpServer() {
    }

    /**
     * 构建 MCP 工具目录。纯工厂方法，不依赖传输层，可直接单测。
     */
    public static List<McpServerFeatures.SyncToolSpecification> tools(AmlTools amlTools) {
        return List.of(
                tool("screen_sanctions",
                        "制裁名单筛查：输入客户姓名，返回是否命中制裁名单及其等级。",
                        schema(stringProperty("customerName", "客户姓名")),
                        request -> amlTools.screenSanctions(stringArgument(request, "customerName"))),

                tool("search_regulations",
                        "按关键词检索 AML 法规条文，返回 evidenceId 与条文内容。风险评级结论应引用该 evidenceId。",
                        schema(stringProperty("query", "检索关键词，例如「一级制裁名单」「结构化拆分」")),
                        request -> amlTools.searchRegulations(stringArgument(request, "query"))),

                tool("query_transactions",
                        "查询客户近 N 个月的交易画像摘要（金额、笔数、时段、跨境特征）。",
                        schema(stringProperty("customerId", "客户内部编号"),
                                integerProperty("months", "回溯月份数，默认 " + DEFAULT_MONTHS)),
                        request -> amlTools.queryTransactions(
                                stringArgument(request, "customerId"), monthsArgument(request))));
    }

    /**
     * 在 stdio 传输上启动 MCP Server，供 MCP 客户端以子进程方式拉起。
     */
    public static McpSyncServer startStdio(AmlTools amlTools) {
        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        return McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools(amlTools))
                .build();
    }

    // ---------- 内部构造 ----------

    private static McpServerFeatures.SyncToolSpecification tool(String name,
                                                               String description,
                                                               Map<String, Object> inputSchema,
                                                               Function<McpSchema.CallToolRequest, String> handler) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(inputSchema)
                        .build())
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .addTextContent(handler.apply(request))
                        .build())
                .build();
    }

    /** 组装 JSON Schema；required 为全部声明的属性 */
    private static Map<String, Object> schema(Map<String, Object>... properties) {
        Map<String, Object> declared = new LinkedHashMap<>();
        for (Map<String, Object> property : properties) {
            declared.putAll(property);
        }
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", declared);
        inputSchema.put("required", List.copyOf(declared.keySet()));
        return inputSchema;
    }

    private static Map<String, Object> stringProperty(String name, String description) {
        return Map.of(name, Map.of("type", "string", "description", description));
    }

    private static Map<String, Object> integerProperty(String name, String description) {
        return Map.of(name, Map.of("type", "integer", "description", description));
    }

    private static String stringArgument(McpSchema.CallToolRequest request, String name) {
        Object value = argument(request, name);
        return value == null ? null : String.valueOf(value);
    }

    private static int monthsArgument(McpSchema.CallToolRequest request) {
        Object value = argument(request, "months");
        return value instanceof Number number ? number.intValue() : DEFAULT_MONTHS;
    }

    private static Object argument(McpSchema.CallToolRequest request, String name) {
        return request.arguments() == null ? null : request.arguments().get(name);
    }
}
