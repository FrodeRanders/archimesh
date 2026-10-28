package org.gautelis.archimesh.model;

/** Retained even when operation payloads are pruned, preserving idempotent retries. */
public record BatchReceipt(String opBatchId, long revisionFrom, long revisionTo, String timestamp) { }
