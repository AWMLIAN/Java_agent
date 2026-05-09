package com.example.demo.service;

import com.example.demo.model.entity.AiConversation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface AgentService {
    /**
     * 注册工具
     */
    public void registerTool(Object toolInstance);

    /**
     * 聊天会话
     */
    public String chat(String threadId,String userMessage);

    /**
     * 找到已创建地会话
     * @param threadId 会话线程ID
     */
    public AiConversation findOrCreateConversation(String threadId,Long userId);

    /**
     * 保存AI 消息
     */
    public void saveAiMessage(Long conversationId, AiMessage aiMessage, TokenUsage tokenUsage);
    /**
     * 保存用户消息
     */
    public void saveUserMessage(Long conversationId,String content);
    /**
     * 保存工具消息
     */
    public void saveToolMessage(Long conversationId, ToolExecutionRequest request,String aiMessageContent,String toolLogRawData,boolean success,Long timeMs);
    /**
     * 加载会话全部消息
     */
    public List<ChatMessage> loadHistoryMessage(Long conversationId);
    /**
     * tool序列化工具
     */
    public String toolCallsToJson(List<ToolExecutionRequest> requests);
    /**
     * tool反序列化工具
     */
    public List<ToolExecutionRequest> parseToolCalls(String toolCallsJson);
}
