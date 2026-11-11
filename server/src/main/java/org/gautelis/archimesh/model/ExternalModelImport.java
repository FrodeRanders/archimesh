package org.gautelis.archimesh.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.gautelis.archimesh.auth.AuthorizationSubject;

/** Trusted server-generated single-element import; never decoded from a client operation payload. */
public record ExternalModelImport(String modelId, String ref, AuthorizationSubject subject, String opBatchId,
                                  String elementId, String archimateType, boolean create,
                                  long expectedRevision, JsonNode operations) { }
