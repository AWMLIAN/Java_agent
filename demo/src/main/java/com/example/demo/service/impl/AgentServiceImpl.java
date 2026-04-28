package com.example.demo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.*;

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
    @Autowired
    private ObjectMapper objectMapper;

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
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId=1L;
        if(auth!=null&& auth.getPrincipal() instanceof AdminUserDetails){
            userId = ((AdminUserDetails) auth.getPrincipal()).getUmsAdmin().getId();
        }
        //1.创建会话记录
        AiConversation conversation = findOrCreateConversation(threadId,userId);
        //2.获取该thread消息历史
        List<ChatMessage> chatMessages = loadHistoryMessage(conversation.getId());
        chatMessages.add(0, SystemMessage.from("你是商城助手，当前用户ID是 " + userId + "。"));
        chatMessages.add(UserMessage.from(userMessage));
        //保存用户信息
        saveUserMessage(conversation.getId(),userMessage);

        //3.ReAct循环 : 推理+执行
        int maxIteration=5;
        for(int i=0;i<maxIteration;i++){
            //构建消息请求，
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(chatMessages)
                    .toolSpecifications(toolSpecifications)
                    .build();
            //发送请求并获取响应
            ChatResponse chatResponse= chatModel.chat(chatRequest);
            AiMessage aiMessage = chatResponse.aiMessage();
            chatMessages.add(aiMessage);
            //保存 assistant 消息到数据库
            saveAiMessage(conversation.getId(),aiMessage);
//          //没有工具调用，直接返回文本
            if(!aiMessage.hasToolExecutionRequests()){
                return aiMessage.text();
            }
            //处理每一个工具调用
            for(ToolExecutionRequest request:aiMessage.toolExecutionRequests()){
                String name = request.name();
                String arguments = request.arguments();
                Object toolInstance = toolInstances.get(name);
                if(toolInstance==null){
                    ToolExecutionResultMessage errorMsg = ToolExecutionResultMessage.from(request, "错误：未知工具" + name);
                    chatMessages.add(errorMsg);
                    saveToolMessage(conversation.getId(),request,errorMsg.text(),false,null);
                    continue;
                }
                //执行工具，捕获异常
                long start = System.currentTimeMillis();
                try{
                    Method method = toolInstance.getClass().getMethod(name, String.class)/* 参数类型暂按具体实现调整 */;
                    String result = method.invoke(toolInstance, arguments).toString();
                    ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(request, result);
                    chatMessages.add(toolResult);
                    long timeMs = System.currentTimeMillis() - start;
                    saveToolMessage(conversation.getId(),request,result,true,timeMs);

                }catch (Exception e){
                    String errorContent="工具执行错误"+e.getMessage();
                    ToolExecutionResultMessage errorResult=ToolExecutionResultMessage.from(request,errorContent);
                    chatMessages.add(errorResult);
                    long timeMs=System.currentTimeMillis()-start;
                    saveToolMessage(conversation.getId(),request,errorContent,false,timeMs);
                }
            }
        }
        return "抱歉，我暂时无法完成您的请求。";
    }

    @Override
    public AiConversation findOrCreateConversation(String threadId,Long userId) {
        AiConversation aiConversation = aiConversationMapper.selectOne(
                new LambdaQueryWrapper<AiConversation>().eq(AiConversation::getThreadId, threadId)
        );
        if(aiConversation==null){
            aiConversation=new AiConversation();
            aiConversation.setStatus((byte)1);
            aiConversation.setUserId(userId);//TODO暂时为1
            aiConversation.setTitle("新对话");
            aiConversation.setThreadId(threadId);
            aiConversation.setCreateTime(new Date());
            aiConversation.setUpdateTime(new Date());
            aiConversationMapper.insert(aiConversation);
        }
        return aiConversation;
    }

    @Override
    public void saveAiMessage(Long conversationId, AiMessage aiMessage) {
        com.example.demo.model.entity.AiMessage aimessage=new com.example.demo.model.entity.AiMessage();
        aimessage.setConversationId(conversationId);
        aimessage.setRole("assistant");
        aimessage.setContent(aiMessage.text());
        //序列化 tool_calls
        if (aiMessage.hasToolExecutionRequests()) {
            aimessage.setToolCalls(toolCallsToJson(aiMessage.toolExecutionRequests()));
        }
        aimessage.setCreateTime(new Date());
        aimessage.setPromptTokens(0);
        aimessage.setCompletionTokens(0);
        aimessage.setTotalTokens(0);
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
    public void saveToolMessage(Long conversationId, ToolExecutionRequest request, String content, boolean success, Long timeMs) {
        com.example.demo.model.entity.AiMessage aiMessage=new com.example.demo.model.entity.AiMessage();
        aiMessage.setConversationId(conversationId);
        aiMessage.setRole("tool");
        aiMessage.setContent(content);
        aiMessage.setToolName(request.name());
        aiMessage.setToolCallId(request.id());
        aiMessage.setCreateTime(new Date());
        aiMessageMapper.insert(aiMessage);

        AiToolLog log = new AiToolLog();
        log.setMessageId(aiMessage.getId());
        log.setToolName(request.name());
        log.setRequestParams(request.arguments());
        log.setResponseData(content);
        log.setSuccess((byte) (success ? 1 : 0));
        if (!success) log.setErrorMsg(content);
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
}
