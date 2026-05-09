package com.example.demo;

import com.example.demo.service.impl.MemoryCompressorServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

public class MemoryCompressorServicelmplTest {
    private MemoryCompressorServiceImpl compressor;

    @BeforeEach
    void setUp() {
        compressor = new MemoryCompressorServiceImpl();
    }

    @Test
    void shouldNotCompressWhenUnderLimit() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是助手"),
                UserMessage.from("你好"),
                AiMessage.from("你好！有什么可以帮你的？")
        );
        List<ChatMessage> result = compressor.compress(messages, 100000);
        assertThat(result).hasSize(3);
    }
    @Test
    void shouldReplaceToolBlockWithSummaryWhenOverLimit() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是助手"));

        // 构建一个大到必然超过限制的消息块
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            largeContent.append("这是一段很长的对话内容用来测试压缩功能。");
        }

        messages.add(UserMessage.from("查询订单"));
        messages.add(AiMessage.from(
                "正在查询...",
                List.of(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("queryRecentOrders")
                        .arguments("{\"userId\":1}")
                        .build())
        ));
        messages.add(ToolExecutionResultMessage.from("call-1", "queryRecentOrders", largeContent.toString()));
        messages.add(AiMessage.from("查询完成，共找到多条订单。"));

        List<ChatMessage> result = compressor.compress(messages, 100);

        // 压缩后应包含摘要系统消息
        boolean hasSummary = result.stream()
                .anyMatch(m -> m instanceof SystemMessage && ((SystemMessage) m).text().contains("历史摘要"));
        assertThat(hasSummary).isTrue();
        assertThat(result.size()).isLessThan(messages.size());
    }

}
