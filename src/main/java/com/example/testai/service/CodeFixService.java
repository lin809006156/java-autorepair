package com.example.testai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CodeFixService {
    public static class FixRound {
        public int step;
        public String userInput;
        public String llmSuggestion;
        public FixRound(int step, String userInput, String llmSuggestion) {
            this.step = step;
            this.userInput = userInput;
            this.llmSuggestion = llmSuggestion;
        }
    }

    public static class FixSession {
        public String sessionId;
        public String originalCode;
        public int step = 1; // 1:代码, 2:用途, 3:错误描述, 4:期望/实际输出
        public List<FixRound> rounds = new ArrayList<>();
        public boolean finished = false;
        public String nextPrompt;
        public FixSession(String sessionId, String originalCode) {
            this.sessionId = sessionId;
            this.originalCode = originalCode;
        }
    }

    private final Map<String, FixSession> sessionMap = new HashMap<>();

    @Autowired
    private ChatClient chatClient;

    // 启动新会话，step=1，用户输入代码，自动AI检查
    public FixSession startSession(String code) {
        String sessionId = UUID.randomUUID().toString();
        FixSession session = new FixSession(sessionId, code);
        // Step1: Is any error in this program?
        String prompt = "Please check the following Java code for errors and briefly list the error points:\n" + code;
        String llmResult = chatClient.prompt().user(prompt).call().content();
        session.rounds.add(new FixRound(1, code, llmResult));
        session.step = 2;
        session.nextPrompt = "Please supplement: This program is used for ... (describe the program purpose)";
        sessionMap.put(sessionId, session);
        return session;
    }

    // 用户每轮输入内容，step递增
    public FixSession feedback(String sessionId, String userInput) {
        FixSession session = sessionMap.get(sessionId);
        if (session == null || session.finished) return null;
        int step = session.step;
        String lastCode = session.originalCode;
        for (int i = session.rounds.size() - 1; i >= 0; i--) {
            String ai = session.rounds.get(i).llmSuggestion;
            if (ai.contains("```java")) {
                int start = ai.indexOf("```java") + 7;
                int end = ai.lastIndexOf("```");
                if (end > start) {
                    lastCode = ai.substring(start, end).trim();
                    break;
                }
            }
        }
        String prompt;
        switch (step) {
            case 2:
                prompt = "Important: User has provided the program purpose, you must improve the code based on the purpose. User input: " + userInput + "\n\nPlease fix the following Java code based on the purpose, output only the complete fixed code:\n" + lastCode;
                break;
            case 3:
                prompt = "Important: User has provided specific error description, you must improve the code based on the error description. User input: " + userInput + "\n\nPlease fix the following Java code based on the error description, output only the complete fixed code:\n" + lastCode;
                break;
            case 4:
                prompt = "Important: User has provided expected output, you must make the code output exactly match user expectations. User input: " + userInput + "\n\nPlease fix the following Java code to match the expected output, output only the complete fixed code:\n" + lastCode;
                break;
            default:
                prompt = "Important: You must improve the code based on user input, even if the code looks correct, try to optimize it. User input: " + userInput + "\n\nPlease fix the following Java code, output only the complete fixed code:\n" + lastCode;
        }
        String llmResult = chatClient.prompt().user(prompt).call().content();
        session.rounds.add(new FixRound(step, userInput, llmResult));
        session.step = step + 1;
        switch (session.step) {
            case 2:
                session.nextPrompt = "Please supplement: This program is used for ... (describe the program purpose)";
                break;
            case 3:
                session.nextPrompt = "Please supplement: There are some errors in ... (describe the specific errors you found)";
                break;
            case 4:
                session.nextPrompt = "Please supplement: The result should be..., but the output is... (describe expected output and actual output)";
                break;
            default:
                session.finished = true;
                session.nextPrompt = "Repair process completed.";
        }
        return session;
    }

    public FixSession getSession(String sessionId) {
        return sessionMap.get(sessionId);
    }
} 