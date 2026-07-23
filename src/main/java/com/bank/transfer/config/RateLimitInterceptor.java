package com.bank.transfer.config;

import com.bank.transfer.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final int MAX_REQUESTS = 10;
    private static final long TIME_WINDOW_SECONDS = 60;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {

        String accountId = extractFromAccountNumber(request);

        if (accountId == null || accountId.isEmpty()) {
            accountId = request.getRemoteAddr();
        }

        String redisKey = "ratelimit:transfer:" + accountId;

        String currentRequests = redisTemplate.opsForValue().get(redisKey);

        if (currentRequests != null && Integer.parseInt(currentRequests) >= MAX_REQUESTS) {
            response.setHeader("Retry-After", String.valueOf(TIME_WINDOW_SECONDS));
            throw new BusinessException(
                HttpStatus.TOO_MANY_REQUESTS,
                "ERR_SYS_429",
                "Too many requests. Please try again later."
            );
        }

        if (currentRequests == null) {
            redisTemplate.opsForValue().set(redisKey, "1", TIME_WINDOW_SECONDS, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().increment(redisKey);
        }

        return true;
    }

    private String extractFromAccountNumber(HttpServletRequest request) {
        try {
            if (request instanceof ContentCachingRequestWrapper wrapper) {
                byte[] buf = wrapper.getContentAsByteArray();
                if (buf.length > 0) {
                    JsonNode jsonNode = objectMapper.readTree(buf);
                    if (jsonNode.has("fromAccountNumber")) {
                        return jsonNode.get("fromAccountNumber").asText();
                    }
                }
            }
        } catch (Exception e) {
            // ปล่อยผ่านถ้า parse ไม่ได้
        }
        return null;
    }
}
