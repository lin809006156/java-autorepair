package com.example.testai.controller;

import com.example.testai.service.CodeFixService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/fix")
public class ChatController {

    private final CodeFixService codeFixService;

    @PostMapping("/start")
    public CodeFixService.FixSession start(@RequestBody CodeInput input) {
        return codeFixService.startSession(input.getCode());
    }

    @PostMapping("/feedback")
    public CodeFixService.FixSession feedback(@RequestBody FeedbackInput input) {
        return codeFixService.feedback(input.getSessionId(), input.getUserInput());
    }

    @GetMapping("/session/{sessionId}")
    public CodeFixService.FixSession getSession(@PathVariable String sessionId) {
        return codeFixService.getSession(sessionId);
    }

    @Data
    public static class CodeInput {
        private String code;
    }

    @Data
    public static class FeedbackInput {
        private String sessionId;
        private String userInput; // 用户输入的内容
    }
}
