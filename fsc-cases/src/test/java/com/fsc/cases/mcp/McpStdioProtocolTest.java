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
 *
 * <p>⚠️ <b>已知残留：跑一次本测试会在本机留下一个不退出的 </b>{@code McpStdioMain}<b> 子进程。</b>
 * 2026-09-22 实测本机累积了 26 个（最早的来自 09-19），已清理。
 *
 * <p>成因与已修/未修的部分，写在 {@code McpStdioMain} 那个 {@code System.exit(0)} 的注释里：
 * 那一行修的是「进程收到关闭信号时被非守护线程拖住、退不出去」，
 * 而 <b>stdio 传输不把 stdin 的 EOF 当成"客户端走了"</b>——把 shutdown hook 换成会打标记的
 * 版本后，关掉 stdin 后 stderr 里从没出现过那个标记，线程转储显示 main 一直停在
 * {@code CountDownLatch.await()}。所以泄漏的另一半在传输层，没修。
 *
 * <p>之所以把它写在这里而不是只写在实现里：<b>这条测试是唯一会拉起那个进程的地方</b>，
 * 而"跑测试会留垃圾进程"这件事此前没有任何地方说过——一个只泄漏不报错的进程
 * 正好落在「客户端用杀进程、不关心它自己退不退」与「测试从没断言过退出」之间。
 *
 * <p>本机验证这条路走不通（换过三种数进程的方法，都因为环境原因量不到东西：
 * Java 的 {@code ProcessHandle} 在这个环境下数出来是 0；从 JVM 里起
 * {@code powershell} 拿不到 PATH）。所以这里**不写一个量不准的断言**——
 * 那只会变成又一条"绿着但什么都没测住"的检查。
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
