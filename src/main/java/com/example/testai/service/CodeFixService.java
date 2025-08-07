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

    // 解析代码前面的注释，提取程序用途和期望输出
    private String[] parseCodeComments(String code) {
        String[] result = new String[2]; // [用途, 期望输出]
        result[0] = "";
        result[1] = "";
        
        String[] lines = code.split("\n");
        StringBuilder codeWithoutComments = new StringBuilder();
        StringBuilder comments = new StringBuilder();
        
        boolean inCommentBlock = false;
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            // 检查多行注释开始
            if (trimmedLine.startsWith("/*")) {
                inCommentBlock = true;
                comments.append(line).append("\n");
                continue;
            }
            
            // 检查多行注释结束
            if (trimmedLine.endsWith("*/")) {
                inCommentBlock = false;
                comments.append(line).append("\n");
                continue;
            }
            
            // 如果在注释块中
            if (inCommentBlock) {
                comments.append(line).append("\n");
                continue;
            }
            
            // 检查单行注释
            if (trimmedLine.startsWith("//")) {
                comments.append(line).append("\n");
                continue;
            }
            
            // 检查是否包含分号的注释（我们的特殊格式）
            if (trimmedLine.startsWith("//") && trimmedLine.contains("；")) {
                comments.append(line).append("\n");
                continue;
            }
            
            // 普通代码行
            codeWithoutComments.append(line).append("\n");
        }
        
        // 从注释中提取用途和期望输出
        String commentText = comments.toString();
        if (commentText.contains("；")) {
            String[] parts = commentText.split("；");
            if (parts.length >= 2) {
                // 提取用途（第一个分号前的内容）
                String purpose = parts[0].replaceAll("//\\s*", "").trim();
                result[0] = purpose;
                
                // 提取期望输出（第二个分号前的内容，如果存在）
                if (parts.length >= 3) {
                    String expectedOutput = parts[1].trim();
                    result[1] = expectedOutput;
                }
            }
        }
        
        return result;
    }

    // 启动新会话，自动解析注释并修复代码
    public FixSession startSession(String code) {
        String sessionId = UUID.randomUUID().toString();
        FixSession session = new FixSession(sessionId, code);
        
        // 解析代码注释
        String[] parsedComments = parseCodeComments(code);
        String purpose = parsedComments[0];
        String expectedOutput = parsedComments[1];
        
        // 构建智能提示
        StringBuilder prompt = new StringBuilder();
        prompt.append("Please analyze and fix the following Java code:\n\n");
        prompt.append(code).append("\n\n");
        
        if (!purpose.isEmpty()) {
            prompt.append("Program purpose: ").append(purpose).append("\n\n");
        }
        
        if (!expectedOutput.isEmpty()) {
            prompt.append("Expected output: ").append(expectedOutput).append("\n\n");
        }
        
        prompt.append("Please:\n");
        prompt.append("1. Check for any errors in the code\n");
        prompt.append("2. Fix the code based on the purpose and expected output\n");
        prompt.append("3. Output only the complete fixed code\n");
        
        String llmResult = chatClient.prompt().user(prompt.toString()).call().content();
        
        // 记录这一轮对话
        session.rounds.add(new FixRound(1, code, llmResult));
        session.step = 2;
        session.finished = false; // 不直接完成，等待用户反馈
        session.nextPrompt = "Please check if the fix is correct. If not, click 'Not Resolved' and provide more details.";
        
        sessionMap.put(sessionId, session);
        return session;
    }

    // 用户每轮输入内容，step递增
    public FixSession feedback(String sessionId, String userInput) {
        FixSession session = sessionMap.get(sessionId);
        if (session == null || session.finished) return null;
        
        // 获取最新的代码
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
        
        // 构建改进提示
        StringBuilder prompt = new StringBuilder();
        prompt.append("The user has provided additional feedback about the code. Please improve the code based on this feedback:\n\n");
        prompt.append("User feedback: ").append(userInput).append("\n\n");
        prompt.append("Current code:\n").append(lastCode).append("\n\n");
        prompt.append("Please:\n");
        prompt.append("1. Analyze the user's feedback\n");
        prompt.append("2. Fix the code according to the feedback\n");
        prompt.append("3. Output only the complete improved code\n");
        
        String llmResult = chatClient.prompt().user(prompt.toString()).call().content();
        
        // 记录这一轮对话
        session.rounds.add(new FixRound(session.step, userInput, llmResult));
        session.step = session.step + 1;
        session.finished = false; // 继续等待用户反馈
        session.nextPrompt = "Please check if this improved version is correct. If not, click 'Not Resolved' and provide more details.";
        
        return session;
    }

    public FixSession getSession(String sessionId) {
        return sessionMap.get(sessionId);
    }
} 