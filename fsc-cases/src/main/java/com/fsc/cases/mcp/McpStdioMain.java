package com.fsc.cases.mcp;

import com.fsc.cases.rag.LegalRag;
import com.fsc.cases.tool.AmlTools;
import io.modelcontextprotocol.server.McpSyncServer;

import java.util.concurrent.CountDownLatch;

/**
 * MCP stdio 服务入口。
 *
 * <p>stdio 是 MCP 最常见的本地接入方式：由客户端把本类作为<b>子进程</b>拉起，
 * 通过标准输入输出交换 JSON-RPC 消息。因此本入口<b>不能</b>向 stdout 打印任何非协议内容
 * —— 日志请走 stderr。
 *
 * <p>本地接入示例（MCP 客户端配置）：
 * <pre>
 * {
 *   "mcpServers": {
 *     "fsc-aml": {
 *       "command": "java",
 *       "args": ["-cp", "&lt;classpath&gt;", "com.fsc.cases.mcp.McpStdioMain"]
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>无需 API Key、无需联网：法规索引与合成数据均在本进程内构建。
 */
public final class McpStdioMain {

    private McpStdioMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        McpSyncServer server = AmlMcpServer.startStdio(new AmlTools(LegalRag.build()));

        CountDownLatch keepAlive = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.closeGracefully();
            keepAlive.countDown();
        }, "mcp-shutdown"));

        System.err.println("[fsc-aml-mcp] stdio 服务已启动，等待 MCP 客户端握手");
        keepAlive.await();
    }
}
