package com.example.echomind.dto;

/**
 * 会话历史中的一条消息，只暴露前端真正用得到的字段。
 *
 * @param role    user / assistant
 * @param content 消息正文（Markdown 原文）
 */
public record ChatMessageVO(String role, String content) {
}
