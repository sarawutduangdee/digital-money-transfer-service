package com.bank.transfer.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer{
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // นำ Interceptor ไปดักเฉพาะเส้น API โอนเงิน
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/api/v1/transfers", "/api/v1/transfers/**");
    }
}
