package com.example.demo.service;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 记忆压缩器接口：将消息列表压缩到指定token上限内
 * 与ToolResultProcessor 权责分离——此接口负责长期记忆降维
 * 可独立切换策略不影响实时对话质量
 */
public interface MemoryCompressorService {
    /**
     * 压缩消息列表，保证压缩后的消息总token数不超过maxToken(估算值)
     * @param messages 原始消息列表
     * @param maxTokens 压缩后token 上限
     * @return 压缩后的消息列表
     */
   List<ChatMessage> compress(List<ChatMessage> messages,int maxTokens);
}
