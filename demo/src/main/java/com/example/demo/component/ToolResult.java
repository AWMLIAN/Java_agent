package com.example.demo.component;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class ToolResult {
    private final String toolCallId;
    private final ToolExecutionResultMessage message;
    private final boolean success;
    private final long timeMs;
}
