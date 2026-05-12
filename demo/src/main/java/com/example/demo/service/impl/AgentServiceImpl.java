package com.example.demo.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.component.ToolExecutor;
import com.example.demo.component.ToolResult;
import com.example.demo.component.ToolResultProcessor;
import com.example.demo.mapper.AiConversationMapper;
import com.example.demo.mapper.AiMessageMapper;
import com.example.demo.mapper.AiToolLogMapper;
import com.example.demo.model.bo.AdminUserDetails;
import com.example.demo.model.dto.AgentEvent;
import com.example.demo.model.entity.AiConversation;
import com.example.demo.model.entity.AiToolLog;
import com.example.demo.service.AgentService;
import com.example.demo.service.MemoryCompressorService;
import com.example.demo.tool.QueryOrdersTool;
import com.example.demo.tool.SearchKnowledgeTool;
import com.example.demo.tool.SearchProductTool;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class AgentServiceImpl implements AgentService {
    @Autowired
    private OpenAiChatModel chatModel;
    @Autowired
    private AiConversationMapper aiConversationMapper;
    @Autowired
    private AiMessageMapper aiMessageMapper;
    @Autowired
    private AiToolLogMapper aiToolLogMapper;
    //工具实例,方法名——>class对象映射
    private final Map<String,Object> toolInstances=new HashMap<>();
    //method缓存，方法名->Method映射
    @Getter
    private final Map<String,Method> toolMethods=new HashMap<>();
    //工具定义列表，描述提供了什么工具
    private final List<ToolSpecification> toolSpecifications=new ArrayList<>();
    //工具执行器
    @Autowired
    private ToolExecutor toolExecutor;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ToolResultProcessor resultProcessor;
    @Autowired
    private MemoryCompressorService memoryCompressorService;
    //请求级锁
    private final ConcurrentHashMap<String, RefCountedLock> threadLocks = new ConcurrentHashMap<>();
    // 约 179,200 字符，作为触发压缩的字符阈值
    private static final int MAX_CHARS = 128_000 * 2 * 70 / 100;
    private static final int MAX_ITERATIONS=5;
    public AgentServiceImpl(SearchProductTool searchProductTool, QueryOrdersTool ordersTool, SearchKnowledgeTool searchKnowledgeTool){
        registerTool(searchProductTool);
        registerTool(ordersTool);
        registerTool(searchKnowledgeTool);
    }
    //将所有工具声明到 toolSpecifications
    //将所有工具方法，建立方法名到类的映射
    @Override
    public void registerTool(Object toolInstance) {
        Class<?> aClass = toolInstance.getClass();
        for(Method method:aClass.getDeclaredMethods()){
            //获取带有@Tool注解的方法
            if(method.isAnnotationPresent(Tool.class)){
                Tool toolAnno=method.getAnnotation(Tool.class);
                //声明工具
                ToolSpecification spec=ToolSpecification.builder()
                        .name(method.getName())
                        .description(toolAnno.value()[0])
                        .build();
                toolSpecifications.add(spec);
                toolInstances.put(method.getName(),toolInstance);
                toolMethods.put(method.getName(),method);
            }
        }
    }

    private String generateTraceId(){
        return "smartmall-"+UUID.randomUUID().toString().substring(0,8);
    }
    private ReentrantLock acquireThreadLock(String threadId){
        RefCountedLock refLock=threadLocks.compute(threadId,(key,existing)->{
            if(existing==null){
                return new RefCountedLock();
            }else{
                return existing;
            }
        });
        ReentrantLock lock = refLock.getLock();
        lock.lock();
        refLock.retain();
        return lock;
    }
    private void releaseThreadLock(String threadId){
        //原子释放+安全移除
        threadLocks.compute(threadId, (key, existing) -> {
            if (existing != null && existing.release()) {
                return null;   // 计数归零，移除条目
            }
            return existing;   // 保持原样
        });
    }
    /**
     * 创建/加载历史对话，组装历史消息
     */
    private ChatContext buildChatContext(String threadId,String userMessage,String traceId){
        Long userId=getUserId();
        //1.创建会话记录
        AiConversation conversation = findOrCreateConversation(threadId,userId);
        //2.获取该thread消息历史
        List<ChatMessage> chatMessages = loadHistoryMessage(conversation.getId());
        chatMessages.add(0, SystemMessage.from("你是商城助手，当前用户ID是 " + userId + "。"));
        chatMessages.add(UserMessage.from(userMessage));
        //保存用户信息
        saveUserMessage(conversation.getId(),userMessage);
        if(shouldCompress(chatMessages)){
            log.info("[{}] MemoryCompressStart beforeSize={} estimatedChars={}",traceId,chatMessages.size(),chatMessages.stream().mapToInt(m->m.toString().length()).sum());
            chatMessages = memoryCompressorService.compress(chatMessages,MAX_CHARS);
            log.info("[{}] MemoryCompressEnd afterSize={} estimatedChars={}",traceId,chatMessages.size(),chatMessages.stream().mapToInt(m->m.toString().length()).sum());
        }
        return new ChatContext(conversation,chatMessages);
    }
    /**
     * ReAct 循环：推理->工具执行->观察->继续推理
     */
    private String executeActLoop(ChatContext ctx,String traceId) {
        // 用数组做 holder，因为 lambda 内不能修改外部局部变量
        String[] resultHolder = new String[1];
        executeReActLoop(ctx, traceId, new ReActCallback() {
            @Override
            public void onThinking(String text) {}

            @Override
            public void onToolCallStart(ToolExecutionRequest request) {}

            @Override
            public void onToolCallEnd(ToolResult result, String processedText) {}

            @Override
            public void onFinalAnswer(String text, TokenUsage tokenUsage) {
                resultHolder[0]=text;
            }

            @Override
            public void onError(String message) {
                resultHolder[0]=message;
            }
        });
        return resultHolder[0];
    }

    /**
     * 寄生压缩
     */
    private void handleToolResults(List<ToolExecutionRequest> requests, List<ToolResult> toolResults, Long id, List<ChatMessage> chatMessages) {
        for (ToolResult toolResult : toolResults) {
            String originalText = toolResult.getMessage().text();
            String processedText = resultProcessor.process(toolResult.getMessage().toolName(), originalText);
            ToolExecutionResultMessage processMsg = ToolExecutionResultMessage.from(
                    toolResult.getToolCallId(),
                    toolResult.getMessage().toolName(),
                    processedText
            );
            chatMessages.add(processMsg);
            // 按 toolCallId 找到对应的原始请求
            ToolExecutionRequest matchedReq = requests.stream()
                    .filter(r -> r.id().equals(toolResult.getToolCallId()))
                    .findFirst()
                    .orElse(null);
            saveToolMessage(id,
                    matchedReq != null ? matchedReq : ToolExecutionRequest.builder()
                            .id(toolResult.getToolCallId()).name("").arguments("").build(),
                    processedText,
                    originalText,
                    toolResult.isSuccess(),
                    toolResult.getTimeMs());
        }
    }

    @Override
    public String chat(String threadId, String userMessage) {
        String traceId=generateTraceId();
        ReentrantLock lock=acquireThreadLock(threadId);
        try{
            log.info("[{}] RequestStart threadId={} message={}",traceId,threadId,userMessage);
            ChatContext ctx = buildChatContext(threadId, userMessage, traceId);
            String result = executeActLoop(ctx, traceId);
            log.info("[{}] RequestEnd result=success", traceId);
            return result;
        }finally {
            lock.unlock();
            releaseThreadLock(threadId);
        }
    }
    private boolean shouldCompress(List<ChatMessage> messages){
        int totalChars = messages.stream().mapToInt(m -> m.toString().length()).sum();
        return totalChars>MAX_CHARS;
    }
    @Override
    public AiConversation findOrCreateConversation(String threadId,Long userId) {
        AiConversation aiConversation = aiConversationMapper.selectOne(
                new LambdaQueryWrapper<AiConversation>().eq(AiConversation::getThreadId, threadId)
        );
        if(aiConversation==null){
            aiConversation=new AiConversation();
            aiConversation.setStatus((byte)1);
            aiConversation.setUserId(userId);
            aiConversation.setTitle("新对话");
            aiConversation.setThreadId(threadId);
            aiConversation.setCreateTime(new Date());
            aiConversation.setUpdateTime(new Date());
            aiConversationMapper.insert(aiConversation);
        }
        return aiConversation;
    }

    @Override
    public void saveAiMessage(Long conversationId, AiMessage aiMessage,TokenUsage tokenUsage) {
        com.example.demo.model.entity.AiMessage aimessage=new com.example.demo.model.entity.AiMessage();
        aimessage.setConversationId(conversationId);
        aimessage.setRole("assistant");
        aimessage.setContent(aiMessage.text());
        //序列化 tool_calls
        if (aiMessage.hasToolExecutionRequests()) {
            aimessage.setToolCalls(toolCallsToJson(aiMessage.toolExecutionRequests()));
        }
        aimessage.setCreateTime(new Date());
        if (tokenUsage != null) {
            aimessage.setPromptTokens(tokenUsage.inputTokenCount());
            aimessage.setCompletionTokens(tokenUsage.outputTokenCount());
            aimessage.setTotalTokens(tokenUsage.totalTokenCount());
        } else {
            aimessage.setPromptTokens(0);
            aimessage.setCompletionTokens(0);
            aimessage.setTotalTokens(0);
        }
        aiMessageMapper.insert(aimessage);
    }

    @Override
    public void saveUserMessage(Long conversationId, String content) {
        com.example.demo.model.entity.AiMessage aiMessage = new com.example.demo.model.entity.AiMessage();
        aiMessage.setCreateTime(new Date());
        aiMessage.setContent(content);
        aiMessage.setRole("user");
        aiMessage.setConversationId(conversationId);
        aiMessageMapper.insert(aiMessage);
    }

    @Override
    public List<ChatMessage> loadHistoryMessage(Long conversationId) {
        LambdaQueryWrapper<com.example.demo.model.entity.AiMessage> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(com.example.demo.model.entity.AiMessage::getConversationId,conversationId)
                .orderByAsc(com.example.demo.model.entity.AiMessage::getCreateTime);
        List<com.example.demo.model.entity.AiMessage> aiMessages = aiMessageMapper.selectList(wrapper);
        ArrayList<ChatMessage> list = new ArrayList<>();
        for (com.example.demo.model.entity.AiMessage aiMessage : aiMessages) {
            switch (aiMessage.getRole()){
                case "user":
                    list.add(UserMessage.from(aiMessage.getContent()));
                    break;
                case "assistant":
                    if(aiMessage.getToolCalls()==null){
                        list.add(AiMessage.from(aiMessage.getContent()));
                    }else{
                        //反序列化 toolCalls
                        try {
                            List<ToolExecutionRequest> toolCalls = parseToolCalls(aiMessage.getToolCalls());
                            list.add(AiMessage.from(aiMessage.getContent(),toolCalls));
                        } catch (Exception e) {
                            // 若还原失败，降级为普通文本消息
                            list.add(AiMessage.from(aiMessage.getContent()));
                        }
                    }
                    break;
                case "tool":
                    list.add(ToolExecutionResultMessage.from(
                            aiMessage.getToolCallId(),
                            aiMessage.getToolName(),
                            aiMessage.getContent()
                    ));
                    break;

            }
        }
        return list;
    }

    @Override
    public void saveToolMessage(Long conversationId, ToolExecutionRequest request, String aiMessageContent,String toolLogRawData, boolean success, Long timeMs) {
        com.example.demo.model.entity.AiMessage aiMessage=new com.example.demo.model.entity.AiMessage();
        aiMessage.setConversationId(conversationId);
        aiMessage.setRole("tool");
        aiMessage.setContent(aiMessageContent);//摘要
        aiMessage.setToolName(request.name());
        aiMessage.setToolCallId(request.id());
        aiMessage.setCreateTime(new Date());
        aiMessageMapper.insert(aiMessage);

        AiToolLog log = new AiToolLog();
        log.setMessageId(aiMessage.getId());
        log.setToolName(request.name());
        log.setRequestParams(request.arguments());
        log.setResponseData(toolLogRawData);
        log.setSuccess((byte) (success ? 1 : 0));
        if (!success) log.setErrorMsg(aiMessageContent);
        log.setCreateTime(new Date());
        log.setExecuteTimeMs(timeMs != null ? timeMs.intValue() : null);
        aiToolLogMapper.insert(log);
    }

    @Override
    public String toolCallsToJson(List<ToolExecutionRequest> requests) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolExecutionRequest req : requests) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", req.id());
            call.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", req.name());
            function.put("arguments", req.arguments());
            call.put("function", function);
            list.add(call);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<ToolExecutionRequest> parseToolCalls(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isEmpty()) {
            return null;
        }
        try {
            List<Map<String, Object>> list = objectMapper.readValue(
                    toolCallsJson, new TypeReference<List<Map<String, Object>>>() {});
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (Map<String, Object> call : list) {
                String id = (String) call.get("id");
                Map<String, Object> function = (Map<String, Object>) call.get("function");
                String name = (String) function.get("name");
                String arguments = (String) function.get("arguments");
                // 构建 ToolExecutionRequest（假设实现类名，验证后可能需要调整包名）
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .id(id)
                        .name(name)
                        .arguments(arguments)
                        .build();
                requests.add(request);
            }
            return requests;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void chatStream(String threadId, String userMessage, SseEmitter emitter) {
        String traceId = generateTraceId();
        ReentrantLock lock = acquireThreadLock(threadId);
        try{
            log.info("[{}] RequestStart threadId={} message={}", traceId, threadId, userMessage);
            //加载历史
            ChatContext ctx=buildChatContext(threadId,userMessage,traceId);
            //流式输出
            executeActLoopStream(ctx,traceId,emitter);
            log.info("[{}] RequestEnd result=success", traceId);
        }catch (Exception e){
            log.error("[{}] ChatStreamError", traceId, e);
            sendEvent(emitter,new AgentEvent("ERROR","服务器内部错误",null,null,System.currentTimeMillis()));
        }finally {
            lock.unlock();
            releaseThreadLock(threadId);
            emitter.complete();
        }

    }

    private void sendEvent(SseEmitter emitter, AgentEvent agentEvent) {
        try{
            emitter.send(SseEmitter.event()
                    .name(agentEvent.getType())
                    .data(objectMapper.writeValueAsString(agentEvent)));
        }catch (IOException e){
            //客户端断开连接，忽略
        }catch (Exception e){
            log.warn("SSE send failed type={}",agentEvent.getType(),e);
        }
    }
    //SSE版 ReAct循环：推理->事件推送->工具执行->事件推送->继续推送
    private void executeActLoopStream(ChatContext ctx, String traceId, SseEmitter emitter) {
        executeReActLoop(ctx,traceId, new ReActCallback() {
            @Override
            public void onThinking(String text) {
                sendEvent(emitter,new AgentEvent(
                        "THINKING", text, null,null, System.currentTimeMillis()));
            }

            @Override
            public void onToolCallStart(ToolExecutionRequest request) {
                String startMsg = "正在" + friendlyToolAction(request.name()) + "...";
                sendEvent(emitter, new AgentEvent("TOOL_CALL_START", startMsg, request.name(),request.id(), System.currentTimeMillis()));
            }

            @Override
            public void onToolCallEnd(ToolResult result, String processedText) {
                sendEvent(emitter, new AgentEvent("TOOL_CALL_END", processedText, result.getMessage().toolName() ,result.getToolCallId(), System.currentTimeMillis()));
            }

            @Override
            public void onFinalAnswer(String text, TokenUsage tokenUsage) {
                sendEvent(emitter, new AgentEvent("FINAL_ANSWER", text, null,null,  System.currentTimeMillis()));
            }

            @Override
            public void onError(String message) {
                sendEvent(emitter, new AgentEvent("ERROR", message, null, null, System.currentTimeMillis()));
            }
        });
    }

    private String friendlyToolAction(String name) {
        return switch (name){
            case "searchProducts"->"搜索商品";
            case "queryRecentOrders" -> "查询订单";
            case "searchKnowledge"->"搜索知识库";
            default -> "执行查询";
        };
    }
    private void executeReActLoop(ChatContext ctx,String traceId,ReActCallback callback){
        for(int i=0;i<MAX_ITERATIONS;i++){
            ChatResponse chatResponse;
            try {
                ChatRequest chatRequest = ChatRequest.builder()
                        .toolSpecifications(toolSpecifications)
                        .messages(ctx.chatMessages)
                        .build();
                chatResponse=chatModel.chat(chatRequest);
            }catch (Exception e){
                log.error("[{}] LLMCallFailed error={}", traceId, e.getMessage());
                callback.onError("AI 服务暂时不可用，请稍后重试");
                return;
            }
            AiMessage aiMessage = chatResponse.aiMessage();
            saveAiMessage(ctx.conversation.getId(),aiMessage,chatResponse.tokenUsage());
            ctx.chatMessages.add(aiMessage);
            if(!aiMessage.hasToolExecutionRequests()){
                TokenUsage tokenUsage = chatResponse.tokenUsage();
                log.info("[{}] RequestEnd result=success token={}/{} total={}",
                        traceId, tokenUsage.inputTokenCount(),
                        tokenUsage.outputTokenCount(), tokenUsage.totalTokenCount());
                callback.onFinalAnswer(aiMessage.text(),tokenUsage);
                return;
            }
            // 思考中
            String thinkingText = aiMessage.text();
            if (thinkingText != null && !thinkingText.isBlank()) {
                callback.onThinking(thinkingText);
            } else {
                callback.onThinking("正在分析您的需求...");
            }

            // 工具调用
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            log.info("[{}] ToolCallStart count={} tools={}", traceId, requests.size(),
                    requests.stream().map(ToolExecutionRequest::name).toList());
            for (ToolExecutionRequest request : requests) {
                callback.onToolCallStart(request);
            }
            //异步调用工具
            List<ToolResult> toolResults = toolExecutor.execute(requests, toolInstances, toolMethods, traceId);
            //寄生压缩
            handleToolResults(requests, toolResults, ctx.conversation.getId(), ctx.chatMessages);
            for (ToolResult toolResult : toolResults) {
                String toolName = toolResult.getMessage().toolName();
                String processedText = resultProcessor.process(toolName, toolResult.getMessage().text());
                callback.onToolCallEnd(toolResult,processedText);
            }
        }
        log.warn("[{}] MaxIterationReached iterations={}", traceId, MAX_ITERATIONS);
        callback.onError("抱歉，处理超时，请稍后再试");
    }

    private Long getUserId(){
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&& auth.getPrincipal() instanceof AdminUserDetails){
            return ((AdminUserDetails) auth.getPrincipal()).getUmsAdmin().getId();
        }
        return null;
    }
    private static class RefCountedLock{
        @Getter
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger refCount = new AtomicInteger(1); // 创建时计数为1

        public void retain() {
            refCount.incrementAndGet();
        }

        public boolean release() {
            return refCount.decrementAndGet() == 0; // 返回true表示计数归零
        }

    }
    @RequiredArgsConstructor
    private static class ChatContext{
        final AiConversation conversation;
        final List<ChatMessage> chatMessages;
    }
    private interface ReActCallback{
        void onThinking(String text);
        void onToolCallStart(ToolExecutionRequest request);
        void onToolCallEnd(ToolResult result, String processedText);
        void onFinalAnswer(String text, TokenUsage tokenUsage);
        void onError(String message);
    }

}
