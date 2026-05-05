package com.example.demo.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

/**
 * 工具执行引擎：并发执行，限流，超时，异常降级，参数校验，结果去重
 */
@Component
public class ToolExecutor {
    private static final Logger log= LoggerFactory.getLogger(ToolExecutor.class);
    private static final int MAX_CONCURRENT=3;
    private static final int TOOL_TIMEOUT_SECONDS=10;

    private final Semaphore semaphore=new Semaphore(MAX_CONCURRENT);
    private final ExecutorService executor= new ThreadPoolExecutor(
            MAX_CONCURRENT,
            MAX_CONCURRENT,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    private final ObjectMapper mapper=new ObjectMapper();

    /**
     * 并发执行工具调用，结果按原始tool_calls顺序返回
     * 同一请求内相同调用(工具名+标准化参数)直接返回缓存结果
     */
    public List<ToolResult> execute(
        List<ToolExecutionRequest> requests,
        Map<String,Object> toolInstances,
        String traceId
    ){
        if(requests.isEmpty()){
            return Collections.emptyList();
        }
        //使用LinkedList 保留原始顺序
        Map<String, CompletableFuture<ToolResult>> futureMap=new LinkedHashMap<>();
        //future 级去重映射，同一请求
        Map<String,CompletableFuture<ToolResult>> futureByCacheKey=new ConcurrentHashMap<>();
        //工具id 到 工具名的映射
        Map<String, String> toolNameMap = new HashMap<>();
        for(ToolExecutionRequest req:requests){
            toolNameMap.put(req.id(), req.name());
            //标准化arguments 用于去重
            String cacheKey = buildCacheKey(req.name(), req.arguments());
            CompletableFuture<ToolResult> executionFuture=futureByCacheKey.get(cacheKey);
            if(executionFuture==null){
                executionFuture=CompletableFuture.supplyAsync(
                        ()->executeSingle(req,toolInstances,traceId),executor
                );
                futureByCacheKey.put(cacheKey,executionFuture);
            }else{
                log.info("[{}] ToolDedup tool={} cacheKey={}", traceId, req.name(), cacheKey);
            }
            CompletableFuture<ToolResult> resultFuture = executionFuture.thenApply(toolResult -> {
                ToolExecutionResultMessage newMsg = ToolExecutionResultMessage.from(
                        req.id(), req.name(), toolResult.getMessage().text()
                );
                return new ToolResult(req.id(), newMsg, toolResult.isSuccess(), toolResult.getTimeMs());
            });
            futureMap.put(req.id(), resultFuture);
        }
        //等待所有结果
        List<ToolResult> results=new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<ToolResult>> entry : futureMap.entrySet()) {
            try{
                ToolResult result = entry.getValue().get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                results.add(result);
            }catch (TimeoutException e){
                log.warn("[{}] ToolTimeout toolCallId={}", traceId, entry.getKey());
                results.add(new ToolResult(entry.getKey(),
                        buildFriendlyError(entry.getKey(),toolNameMap.getOrDefault(entry.getKey(), ""),"查询超时，请稍后重试"),
                        false,TOOL_TIMEOUT_SECONDS*1000));
            }catch (Exception e){
                log.error("[{}] ToolError toolCallId={}", traceId, entry.getKey(), e);
                results.add(new ToolResult(entry.getKey(),
                        buildFriendlyError(entry.getKey(),toolNameMap.getOrDefault(entry.getKey(), "") ,"查询暂时失败，请稍后重试"),
                        false,0));
            }
        }
        return results;
    }

    private ToolResult executeSingle(ToolExecutionRequest request, Map<String, Object> toolInstances, String traceId) {
//      //获取一个线程资源，否则阻塞
        semaphore.acquireUninterruptibly();
        long start = System.currentTimeMillis();
        try{
            Object tool=toolInstances.get(request.name());
            if(tool==null){
                log.warn("[{}] ToolUnknow tool={}",traceId,request.name());
                return new ToolResult(request.id(),
                        buildFriendlyError(request.id(), request.name(),"工具不存在"),
                        false, System.currentTimeMillis() - start);
            }
            //1，设置参数校验
            String validationError = validateArgs(request.name(), request.arguments());
            if(validationError!=null){
                log.warn("[{}] ToolArgInvalid tool={} reason={}", traceId, request.name(), validationError);
                return new ToolResult(request.id(),
                        buildFriendlyError(request.id(),request.name(), validationError),
                        false, System.currentTimeMillis() - start);
            }
            //2.反射调用
            Method method = tool.getClass().getMethod(request.name(), String.class);
            String rawResult = (String) method.invoke(tool, request.arguments());
            long timeMs = System.currentTimeMillis() - start;
            log.info("[{}] ToolEnd tool={} timeMs={} success=true",traceId,request.name(),timeMs);
            return new ToolResult(request.id(),
                    ToolExecutionResultMessage.from(request, rawResult),
                    true, timeMs);
        }catch (Exception e){
            long timeMs = System.currentTimeMillis() - start;
            log.warn("[{}] ToolEnd tool={} timeMs={} success=false error={}",traceId,request.name(),timeMs,e.getMessage());
            return new ToolResult(request.id(),
                    buildFriendlyError(request.id(), request.name(),"查询暂时失败，请稍后重试"),
                    false, timeMs);
        }finally {
            semaphore.release();
        }
    }
    /**
     * 参数前置校验，非法参数直接返回友好错误提示，避免无效下游调用
     */
    private String validateArgs(String toolName,String arguments){
        if(arguments==null||arguments.isBlank()){
            return "工具参数不能为空";
        }
        try{
            Map<String, Object> params = mapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
            switch (toolName){
                case "searchProducts":
                    return validateSearchProductArgs(params);
                case "queryRecentOrders":
                    return validateQueryOrdersArgs(params);
                default:
                    return null;
            }
        }catch (Exception e){

        }
        return null;
    }

    private String validateQueryOrdersArgs(Map<String, Object> params) {
        if(!params.containsKey("userId")||params.get("userId")==null){
            return "请提供用户ID";
        }
        if(params.containsKey("limit")&&params.get("limit")!=null){
            try{
                int limit=Integer.parseInt(params.get("limit").toString());
                if(limit<=0) return "查询数量必须大于0";
                if(limit>100) return "单次最多查询100条订单";
            }catch (Exception e){
                return "查询数量格式不正确，请输入数字";
            }
        }
        return null;
    }

    private String validateSearchProductArgs(Map<String, Object> params) {
        Double minPrice=null;
        Double maxPrice=null;
        if(params.containsKey("minPrice")&&params.get("minPrice")!=null){
            try{
                minPrice=Double.parseDouble(params.get("minPrice").toString());
                if(minPrice<0) return "价格不能为负数，请重新输入";
            }catch (Exception e){
                return "价格格式不正确，请输入文字";
            }
        }
        if(params.containsKey("maxPrice")&&params.get("maxPrice")!=null){
            try{
                maxPrice=Double.parseDouble(params.get("maxPrice").toString());
                if(maxPrice<0) return "价格不能为负数，请重新输入";
            }catch (Exception e){
                return "价格格式不正确，请输入数字";
            }
        }
        if(minPrice!=null&&maxPrice!=null&&minPrice>maxPrice){
            return "最低价格不能高于最高价格，请重新输入";
        }
        return null;
    }

    /**
     * 返回友好错误信息
     */
    private ToolExecutionResultMessage buildFriendlyError(String toolCallId,String toolName ,String friendlyMessage) {
        return ToolExecutionResultMessage.from(
                ToolExecutionRequest.builder()
                        .id(toolCallId)
                        .name(toolName)
                        .arguments("")
                        .build(), friendlyMessage
        );
    }
    /**
     * JSON标准化后拼接Key,避免参数顺序影响去重
     */
    private String buildCacheKey(String toolName,String arguments){
        try{
            //将arguments 反序列化为参数
            Map<String,Object> params=mapper.readValue(arguments, new TypeReference<Map<String, Object>>(){});
            //将params 序列化为字符串
            String normalizedArgs = mapper.writeValueAsString(new TreeMap<>(params));
            return toolName+":"+normalizedArgs;
        }catch (Exception e){
            return toolName + ":" + arguments;
        }
    }
}




















