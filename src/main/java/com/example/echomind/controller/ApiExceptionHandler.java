package com.example.echomind.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 把业务异常翻译成干净的 JSON，前端不用再去猜 HTML 错误页。
 *
 * <p>刻意<b>不</b>兜底 {@code Exception.class}：那样会把 Spring 自己的
 * 404 / 405 之类也吞成 500，反而更难排查。
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 参数问题：不支持的格式、文件太大、空文件… */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        log.warn("请求参数不合法：{}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** 超过 Spring 的 multipart 上限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传超过大小限制：{}", e.getMessage());
        // Spring 7 把 PAYLOAD_TOO_LARGE 改名成了 CONTENT_TOO_LARGE（同一个 413）
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("error", "文件超过上传大小限制，请调整 spring.servlet.multipart.max-file-size"));
    }

    /** 环境问题：维度不一致、向量库写入失败… */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> serverError(IllegalStateException e) {
        log.error("RAG 处理失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage() == null ? "内部错误" : e.getMessage()));
    }
}
