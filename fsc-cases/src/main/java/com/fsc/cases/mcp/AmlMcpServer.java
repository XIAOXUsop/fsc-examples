package com.fsc.cases.mcp;

import com.fsc.cases.tool.AmlTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
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
                        new SchemaBuilder()
                                .required("customerName", stringProperty("customerName", "客户姓名"))
                                .build(),
                        request -> amlTools.screenSanctions(stringArgument(request, "customerName"))),

                tool("search_regulations",
                        "按关键词检索 AML 法规条文，返回 evidenceId 与条文内容。风险评级结论应引用该 evidenceId。",
                        new SchemaBuilder()
                                .required("query", stringProperty(
                                        "query", "检索关键词，例如「一级制裁名单」「结构化拆分」"))
                                .build(),
                        request -> amlTools.searchRegulations(stringArgument(request, "query"))),

                tool("query_transactions",
                        "查询客户近 N 个月的交易画像摘要（金额、笔数、时段、跨境特征）。"
                                + "months 不传时默认 " + DEFAULT_MONTHS + " 个月。",
                        // `months` 刻意**不放进 required**。
                        //
                        // schema 原先由 `schema(...)` 组装，而那个方法把声明的每个属性
                        // 都塞进 `required`——于是描述里写着"默认 3"，契约上却是必填：
                        // 一个照着 description 办事、不传 months 的合规客户端会被
                        // 协议层的入参校验直接拒掉，而"默认 3"那句在任何合规客户端上
                        // **一次都不可达**。写着一个行为，契约上却禁止走到它。
                        new SchemaBuilder()
                                .required("customerId", stringProperty("customerId", "客户内部编号"))
                                .addOptional(integerProperty(
                                        "months", "回溯月份数，默认 " + DEFAULT_MONTHS))
                                .build(),
                        request -> amlTools.queryTransactions(
                                stringArgument(request, "customerId"), monthsArgument(request))));
    }

    /**
     * 一个 JSON Schema 的构造器：**声明属性**与**声明必填**分开。
     *
     * <p>{@code required} 只放名字里点名要求的那些；可选属性走 {@link #addOptional}。
     * 原先只有一个 {@code schema(...)}，它把传进去的每个属性都算作必填——
     * 那样"可选参数"在这个服务器上根本无法表达。
     */
    private static final class SchemaBuilder {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        SchemaBuilder required(String name, Map<String, Object> property) {
            properties.put(name, property);
            required.add(name);
            return this;
        }

        SchemaBuilder addOptional(Map<String, Object> property) {
            properties.putAll(property);
            return this;
        }

        Map<String, Object> build() {
            Map<String, Object> inputSchema = new LinkedHashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", properties);
            if (!required.isEmpty()) {
                inputSchema.put("required", List.copyOf(required));
            }
            return inputSchema;
        }
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
