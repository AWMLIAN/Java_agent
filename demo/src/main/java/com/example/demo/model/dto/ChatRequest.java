package com.example.demo.model.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private String threadId;
    private String message;

}
