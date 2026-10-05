package com.portfolio.payments.domain;

/**
 * Lançada quando uma requisição com header {@code If-Match} não confere com a versão atual do recurso.
 * Resulta em HTTP 412 Precondition Failed.
 */
public class PreconditionFailedException extends RuntimeException {

    private final String expectedETag;
    private final String currentETag;

    public PreconditionFailedException(String expectedETag, String currentETag) {
        super("Precondition failed: expected If-Match " + expectedETag + " does not match current ETag " + currentETag);
        this.expectedETag = expectedETag;
        this.currentETag = currentETag;
    }

    public String expectedETag() {
        return expectedETag;
    }

    public String currentETag() {
        return currentETag;
    }
}
