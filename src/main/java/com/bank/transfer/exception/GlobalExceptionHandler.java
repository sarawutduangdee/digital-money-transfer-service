package com.bank.transfer.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    private String getTraceId(HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader(TRACE_ID_HEADER);
        }
        return traceId != null ? traceId : "unknown";
    }

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());

        String typePath = ex.getErrorCode().toLowerCase().replace("_", "-");
        problemDetail.setType(URI.create(ERROR_BASE_URL + typePath));

        // 📌 ปรับ Title ให้เปลี่ยนตาม HTTP Status สวยงาม
        if (ex.getStatus() == HttpStatus.CONFLICT) {
            problemDetail.setTitle("Conflict");
        } else if (ex.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY) {
            problemDetail.setTitle("Unprocessable Entity");
        } else {
            problemDetail.setTitle("Business Rule Violation");
        }

        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request));

        problemDetail.setProperty("errorCode", ex.getErrorCode());
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFoundException(AccountNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );
        problemDetail.setType(URI.create(ERROR_BASE_URL + "account-not-found"));
        problemDetail.setTitle("Account Not Found");

        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request));

        problemDetail.setProperty("errorCode", "ERR_ACC_000");
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        String firstErrorMessage = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            firstErrorMessage
        );
        problemDetail.setType(URI.create(ERROR_BASE_URL + "invalid-account-data"));
        problemDetail.setTitle("Unprocessable Entity");

        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request));
        problemDetail.setProperty("errorCode", "ERR_REQ_002");
        problemDetail.setProperty("invalidFields", errors);
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Malformed JSON request body or unparseable payload"
        );
        problemDetail.setType(URI.create(ERROR_BASE_URL + "bad-request"));
        problemDetail.setTitle("Bad Request");

        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request));
        problemDetail.setProperty("errorCode", "ERR_REQ_003");
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeaderException(MissingRequestHeaderException ex, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Required request header '" + ex.getHeaderName() + "' is not present"
        );
        problemDetail.setType(URI.create(ERROR_BASE_URL + "missing-header"));
        problemDetail.setTitle("Bad Request");

        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("traceId", getTraceId(request));

        problemDetail.setProperty("errorCode", "ERR_REQ_001");
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

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