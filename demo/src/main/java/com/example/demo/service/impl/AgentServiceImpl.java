package com.example.demo.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.component.ToolExecutor;
import com.example.demo.component.ToolResult;
import com.example.demo.component.ToolResultProcessor;
import com.example.demo.mapper.AiConversationMapper;
import com.example.demo.mapper.AiMessageMapper;
import com.example.demo.mapper.AiToolLogMapper;
import com.example.demo.model.bo.AdminUserDetails;
import com.example.demo.model.entity.AiConversation;
import com.example.demo.model.entity.AiToolLog;
import com.example.demo.service.AgentService;
import com.example.demo.tool.QueryOrdersTool;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    //工具实例,方法名——>类映射
    private final Map<String,Object> toolInstances=new HashMap<>();
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
    private MemoryCompressorServiceImpl memoryCompressorService;
    //请求级锁
    private final ConcurrentHashMap<String, RefCountedLock> threadLocks = new ConcurrentHashMap<>();
    // 约 179,200 字符，作为触发压缩的字符阈值
    private static final int MAX_CHARS = 500;//128_000 * 2 * 70 / 100

    public AgentServiceImpl(SearchProductTool searchProductTool, QueryOrdersTool ordersTool){
        registerTool(searchProductTool);
        registerTool(ordersTool);
    }


    //将所有工具声名刀 toolSpecifications
    //将所有工具方法，建立方法名到类的映射
    @Override
    public void registerTool(Object toolInstance) {
        Class<?> aClass = toolInstance.getClass();
        for(Method method:aClass.getDeclaredMethods()){
            //获取带有@Tool注解的方法
            if(method.isAnnotationPresent(Tool.class)){
                Tool toolAnno=method.getAnnotation(Tool.class);
                //声名工具
                ToolSpecification spec=ToolSpecification.builder()
                        .name(method.getName())
                        .description(toolAnno.value()[0])
                        .build();
                toolSpecifications.add(spec);
                toolInstances.put(method.getName(),toolInstance);
            }
        }
    }

    @Override
    public String chat(String threadId, String userMessage) {
        String traceId="smartmall-"+UUID.randomUUID().toString().substring(0,8);
        RefCountedLock refLock=threadLocks.compute(threadId,(key,existing)->{
            if(existing==null){
                return new RefCountedLock();
            }else{
                existing.retain();
                return existing;
            }
        });
        ReentrantLock lock = refLock.getLock();
        lock.lock();
        try{
            log.info("[{}] RequestStart threadId={} message={}",traceId,threadId,userMessage);
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
            //3.ReAct循环 : 推理+执行
            int maxIteration=5;
            for(int i=0;i<maxIteration;i++){
                //构建消息请求，
                ChatRequest chatRequest = ChatRequest.builder()
                        .messages(chatMessages)
                        .toolSpecifications(toolSpecifications)
                        .build();
                //发送请求并获取响应
                ChatResponse chatResponse;
                try{
                    chatResponse=chatModel.chat(chatRequest);
                }catch (Exception e){
                    log.error("[{}] LLMCallFailed error={}",traceId,e.getMessage());
                    return "AI 服务暂时不可用，请稍后重试";
                }
                AiMessage aiMessage = chatResponse.aiMessage();
                // 并发保护：当前 chatMessages 是请求级变量，ReAct 循环同步执行，无并发问题
                // 若未来改为异步处理工具结果，需对 chatMessages 加锁或使用线程安全容器
                chatMessages.add(aiMessage);
                //保存 assistant 消息到数据库
                saveAiMessage(conversation.getId(),aiMessage,chatResponse.tokenUsage());
                //没有工具调用，直接返回文本
                if(!aiMessage.hasToolExecutionRequests()){
                    saveAiMessage(conversation.getId(),aiMessage,chatResponse.tokenUsage());
                    TokenUsage tokenUsage = chatResponse.tokenUsage();
                    log.info("[{}] RequestEnd result=success token={}/{} total={}",
                            traceId,
                            tokenUsage.inputTokenCount(),
                            tokenUsage.outputTokenCount(),
                            tokenUsage.totalTokenCount()
                    );
                    return aiMessage.text();
                }
                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                log.info("[{}] ToolCallStart count={} tools={}",traceId,requests.size(),requests.stream().map(ToolExecutionRequest::name).toList());

                //并发执行
                List<ToolResult> toolResults = toolExecutor.execute(requests, toolInstances, traceId);

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
                    saveToolMessage(conversation.getId(),
                            matchedReq != null ? matchedReq : ToolExecutionRequest.builder()
                                    .id(toolResult.getToolCallId()).name("").arguments("").build(),
                            processedText,
                            originalText,
                            toolResult.isSuccess(),
                            toolResult.getTimeMs());
                }
            }
            log.warn("[{}] MaxIterationReached",traceId);
            return "抱歉，处理超时，请稍后再试。";
        }finally {
            lock.unlock();
            //原子释放+安全移除
            threadLocks.compute(threadId, (key, existing) -> {
                if (existing != null && existing.release()) {
                    return null;   // 计数归零，移除条目
                }
                return existing;   // 保持原样
            });
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
                        //反序列话 toolCalls
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

}
