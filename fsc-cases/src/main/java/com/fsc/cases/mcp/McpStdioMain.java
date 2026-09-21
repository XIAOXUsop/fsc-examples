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

        // ── 走到这里说明这个进程已经该结束了（latch 被数到 0）──────────────────
        //
        // 原先没有这一行。`server.closeGracefully()` 关的是**服务端**，
        // 而 MCP SDK 那些非守护线程（stdio 传输的读线程、调度器）不在它关的范围内：
        // 它们会一直挂着，于是 JVM **永远不退出**。
        //
        // 泄漏是实测到的（2026-09-22，本机）：进程列表里躺着 **25 个**
        // `McpStdioMain` 的 java 进程，最早的来自 09-19——每次跑
        // `McpStdioProtocolTest` 都留下一个。为什么一直没被发现：MCP 客户端用的是
        // "杀掉子进程"，不关心它自己会不会退；而测试从没断言过"退出"这件事。
        //
        // ── 但这一行**只堵住一半**，别把它当成完整修复 ────────────────────────
        //
        // 实测：把 shutdown hook 换成会打标记的版本，再起进程 → initialize →
        // **关掉 stdin**，stderr 里 **从没出现过那个标记**，线程转储显示 main 一直
        // 停在 `CountDownLatch.await()`。也就是说 stdio 传输**没有**把 stdin 的
        // EOF 当成"客户端走了"——它不触发 shutdown hook，这行 `System.exit` 也就
        // 永远走不到。泄漏的另一半在传输层，不在这个入口。
        //
        // 所以这一行的实际作用是：**当进程确实收到了关闭信号时，真的退出去**
        // （原先即使收到信号，非守护线程也会把 JVM 拖住不放）。
        // EOF 那一半没修，代码里也不假装修了。
        //
        // 这里用 `System.exit(0)` 而不是 `return`：返回 main 之后 JVM 也只是
        // 等待所有非守护线程结束——那个等待正是问题本身。
        System.exit(0);
    }
}
