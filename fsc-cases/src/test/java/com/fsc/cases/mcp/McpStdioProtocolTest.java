package com.fsc.cases.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP <b>协议层</b>端到端测试。
 *
 * <p>与 {@link AmlMcpServerTest}（校验工具契约）不同，本测试把服务作为<b>独立子进程</b>拉起，
 * 用官方 MCP 客户端完成完整的 JSON-RPC 握手，验证的是真实可用的 MCP 服务，
 * 而不只是"函数能被调用"。
 *
 * <p>全离线：无网络、无 API Key。
 */
class McpStdioProtocolTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void completesHandshakeListsToolsAndCallsOneOverStdio() {
        ServerParameters parameters = ServerParameters.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), McpStdioMain.class.getName())
                .build();

        try (McpSyncClient client = McpClient
                .sync(new StdioClientTransport(parameters, McpJsonDefaults.getMapper()))
                .clientInfo(new McpSchema.Implementation("fsc-test-client", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .build()) {

            McpSchema.InitializeResult handshake = client.initialize();
            assertNotNull(handshake, "initialize 应返回握手结果");
            assertEquals(AmlMcpServer.SERVER_NAME, handshake.serverInfo().name());
            assertNotNull(handshake.capabilities().tools(), "服务端应声明 tools 能力");

            McpSchema.ListToolsResult listed = client.listTools();
            assertEquals(3, listed.tools().size(), "应对外暴露 3 个工具");
            assertTrue(listed.tools().stream().anyMatch(tool -> tool.name().equals("screen_sanctions")));

            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("search_regulations")
                    .arguments(Map.of("query", "一级制裁名单"))
                    .build());

            assertFalse(Boolean.TRUE.equals(result.isError()), "工具调用不应报错");
            String text = result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(content -> ((McpSchema.TextContent) content).text())
                    .reduce("", String::concat);
            assertTrue(text.contains("AML-"), "应返回带 evidenceId 的法规条文，实际：" + text);
        }
    }

    private static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}
