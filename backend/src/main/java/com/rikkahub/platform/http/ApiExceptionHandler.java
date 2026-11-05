package com.rikkahub.platform.http;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.NoSuchElementException;
import java.util.ConcurrentModificationException;
import java.util.concurrent.RejectedExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handle(Exception error, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        // Spring marks an async response unusable even when the container's committed
        // flag is false.
        // Such a response cannot be written to, including headers or a JSON error body.
        if (error instanceof AsyncRequestNotUsableException)
            return null;
        if (response.isCommitted()) {
            if (DisconnectedClientHelper.isClientDisconnectedException(error))
                return null;
            // Do not hide genuine application errors after commitment; let the container
            // log them.
            throw error;
        }
        return handle(error, request);
    }

    /** Compatibility for callers that only format a pre-stream error. */
    public ResponseEntity<ProblemDetail> handle(Exception error, HttpServletRequest request) {
        int status = 500;
        String code = "INTERNAL_ERROR", detail = "The request could not be completed.";
        if (error instanceof ApiFailure failure) {
            status = failure.status();
            code = failure.code();
            detail = failure.getMessage();
        } else if (error instanceof NoSuchElementException) {
            status = 404;
            code = "NOT_FOUND";
            detail = "The requested resource does not exist.";
        } else if (error instanceof ConcurrentModificationException) {
            status = 409;
            code = "VERSION_CONFLICT";
            detail = "The resource changed; refresh and try again.";
        } else if (error instanceof IllegalArgumentException || error instanceof HttpMessageNotReadableException
                || error instanceof MissingRequestHeaderException
                || error instanceof MethodArgumentTypeMismatchException) {
            status = 400;
            code = "INVALID_REQUEST";
            detail = "The request is invalid.";
        } else if (error instanceof RejectedExecutionException) {
            status = 503;
            code = "OVERLOADED";
            detail = "The service is busy; retry later.";
        }
        String trace = UUID.randomUUID().toString();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", trace);
        problem.setProperty("details", Map.of());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).header("X-Trace-Id", trace)
                .body(problem);
    }
}
