package org.gautelis.archimesh.wire.outbound;

public record ErrorMessage(String code, String message, String opBatchId) {
    public ErrorMessage(String code, String message) {
        this(code, message, null);
    }
}
