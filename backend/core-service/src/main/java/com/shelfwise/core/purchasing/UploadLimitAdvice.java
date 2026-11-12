package com.shelfwise.core.purchasing;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * An upload far over the limit is stopped by the multipart reader itself (it refuses to keep reading a part past
 * the configured size) before the invoice check runs. Without this it would surface as a 500; it is the same
 * "over 20 MB" refusal as any other oversize file: HTTP 413 with a readable message.
 */
@RestControllerAdvice(assignableTypes = InvoiceController.class)
public class UploadLimitAdvice {

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<ProblemDetail> tooLarge() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The file is over the 20 MB limit");
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
    }
}
