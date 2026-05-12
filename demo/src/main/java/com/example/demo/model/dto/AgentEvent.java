package com.example.demo.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentEvent {
    /**
     * 事件类型THINKING | TOOL_CALL_START | TOOL_CALL_END | FINAL_ANSWER | ERROR
     */
    private String type;
    private String content;
    private String toolName;
    private String toolCallId;
    private long timestamp;

}
