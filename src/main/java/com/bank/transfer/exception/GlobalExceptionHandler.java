package com.bank.transfer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. ดักจับ BusinessException ของเราเอง (404, 409, 422)
    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problemDetail.setTitle("Business Rule Violation");
        problemDetail.setType(URI.create("https://bank.com/errors/" + ex.getErrorCode()));

        problemDetail.setProperty("errorCode", ex.getErrorCode());
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    // 2. ดักจับกรณี Body ผิดรูปแบบ (400 Bad Request) จาก @Valid
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request body format");
        problemDetail.setTitle("Bad Request");
        problemDetail.setType(URI.create("https://bank.com/errors/ERR_REQ_002"));

        problemDetail.setProperty("errorCode", "ERR_REQ_002");
        problemDetail.setProperty("invalidFields", errors); //
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeaderException(MissingRequestHeaderException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Required request header '" + ex.getHeaderName() + "' is not present"
        );
        problemDetail.setTitle("Bad Request");
        problemDetail.setType(URI.create("https://bank.com/errors/ERR_REQ_001"));
        problemDetail.setProperty("errorCode", "ERR_REQ_001");
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }

    // 3. ดักจับ Error ทั่วไป (500)
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please try again later."
        );
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("https://bank.com/errors/ERR_SYS_999"));
        problemDetail.setProperty("errorCode", "ERR_SYS_999");
        problemDetail.setProperty("timestamp", Instant.now());

        return problemDetail;
    }
}
