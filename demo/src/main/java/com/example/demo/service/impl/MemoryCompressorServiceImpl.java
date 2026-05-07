package com.example.demo.service.impl;

import com.example.demo.service.MemoryCompressorService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MemoryCompressorServiceImpl implements MemoryCompressorService {

    @Override
    public List<ChatMessage> compress(List<ChatMessage> messages, int maxChars) {
        ArrayList<ChatMessage> result = new ArrayList<>(messages);
        while(estimateChars(result)>maxChars){
            int blockStart=-1;
            int blockEnd=-1;
            //扫描逻辑依赖请求串行化保证消息连续性（tool紧跟着assistant）
            //若未来解除串行化，需改为全局扫描匹配tool_call_id
            for(int i=0;i<result.size();i++){
                if(result.get(i) instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()){
                    Set<String> callIds = aiMsg.toolExecutionRequests()
                            .stream()
                            .map(ToolExecutionRequest::id)
                            .collect(Collectors.toSet());
                    int j=i+1;
                    while(j<result.size()&&result.get(j) instanceof ToolExecutionResultMessage toolMsg&&callIds.contains(toolMsg.id())){
                        j++;
                    }
                    //接下来是Assistant 纯文本回复
                    if(j<result.size()&&result.get(j) instanceof AiMessage finalAssistant &&!finalAssistant.hasToolExecutionRequests()){
                        blockStart=i;
                        blockEnd=j;
                        break;
                    }
                }
            }
            if(blockStart==-1)break;
            log.info("压缩前消息数:{}，估算tokens:{}",result.size(),estimateChars(result));
            String summary = buildBlockSummary(result.subList(blockStart, blockEnd + 1));
            result.subList(blockStart,blockEnd+1).clear();
            result.add(blockStart, SystemMessage.from("[历史摘要]"+summary));
            log.info("压缩后消息数:{},估算tokens:{}",result.size(),estimateChars(result));
        }
        return result;
    }
//    private int estimateTokens(List<ChatMessage> messages){
//        return messages.stream().mapToInt(m->m.toString().length()).sum()/2;
//    }
    private int estimateChars(List<ChatMessage> messages){
        return messages.stream().mapToInt(m->m.toString().length()).sum();
    }
    private String buildBlockSummary(List<ChatMessage> block){
        List<String> toolSummaries = new ArrayList<>();
        for (ChatMessage msg : block) {
            if(msg instanceof ToolExecutionResultMessage toolMsg){
                String text=toolMsg.text();
                if(text!=null&&!text.isBlank()){
                    String snippet = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                    toolSummaries.add(snippet);
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        // 用分隔符拼接，避免尾部多余分隔符
        summary.append(String.join("；", toolSummaries));
        ChatMessage last = block.get(block.size() - 1);
        if(last instanceof AiMessage aiMsg&&!aiMsg.hasToolExecutionRequests()){
            String replyText = aiMsg.text();
            if(replyText!=null&&!replyText.isBlank()){
                String snippet=replyText.length()>100?replyText.substring(0,100)+"...":replyText;
                summary.append("系统回复：").append(snippet);
            }
        }
        return summary.toString();
    }
}
