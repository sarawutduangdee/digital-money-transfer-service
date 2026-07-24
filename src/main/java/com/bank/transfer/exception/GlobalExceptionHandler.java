package com.bank.transfer.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String ERROR_BASE_URL = "https://errors.bank.local/";
    private static final String TRACE_ID_HEADER = "X-Request-Id";

    // Helper Method สำหรับดึง Trace ID จาก MDC (หรือ Header)
    private String getTraceId(HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader(TRACE_ID_HEADER);
        }
        return traceId != null ? traceId : "unknown";
    }

    // 1. ดักจับ BusinessException ของเราเอง (404, 409, 422)
    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());

        // กำหนด Type ตามโจทย์: แปลง ErrorCode เป็น URL เช่น ERR_RULE_001 -> err-rule-001
        String typePath = ex.getErrorCode().toLowerCase().replace("_", "-");
        problemDetail.setType(URI.create(ERROR_BASE_URL + typePath));

        // ตั้ง Title เป็นรายละเอียดย่อๆ (ตามโจทย์ต้องการ title แบบสื่อความหมาย)
        // ถ้า Exception คุณมี field Title สามารถดึงมาใส่แทนค่า Hardcode ได้ครับ
        problemDetail.setTitle("Business Rule Violation");

        // กำหนด instance และ traceId ตามสเปค PDF
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request));

        problemDetail.setProperty("errorCode", ex.getErrorCode());
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    // 2. ดักจับกรณี Body ผิดรูปแบบ (400 Bad Request) จาก @Valid
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request body format");
        problemDetail.setType(URI.create(ERROR_BASE_URL + "bad-request"));
        problemDetail.setTitle("Bad Request");

        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request));

        problemDetail.setProperty("errorCode", "ERR_REQ_002");
        problemDetail.setProperty("invalidFields", errors);
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    // ดักจับกรณี Header X-Request-Id หาย หรือ Header อื่นๆ หาย
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeaderException(MissingRequestHeaderException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Required request header '" + ex.getHeaderName() + "' is not present"
        );
        problemDetail.setType(URI.create(ERROR_BASE_URL + "missing-header"));
        problemDetail.setTitle("Bad Request");

        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request)); // อาจจะดึงได้เป็น unknown ถ้า Client ไม่ส่งมาและยังไม่ผ่าน Filter

        problemDetail.setProperty("errorCode", "ERR_REQ_001");
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    // 3. ดักจับ Error ทั่วไป (500)
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please try again later."
        );
        problemDetail.setType(URI.create(ERROR_BASE_URL + "internal-server-error"));
        problemDetail.setTitle("Internal Server Error");

        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request));

        problemDetail.setProperty("errorCode", "ERR_SYS_999");
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }
}