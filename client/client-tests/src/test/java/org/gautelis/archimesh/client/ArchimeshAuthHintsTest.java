package org.gautelis.archimesh.client;

import org.gautelis.archimesh.plugin.util.ArchimeshAuthHints;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ArchimeshAuthHintsTest {

    @Test
    void ownedFieldRejectionPointsToExplicitCentralCommand() {
        Assertions.assertTrue(ArchimeshAuthHints.describeServerError("EXTERNAL_FIELD_OWNED", "rejected", false, false).contains("Propose/change central data"));
    }

    @Test
    void tokenExpiryRecognizesExpiredJwt() {
        String token = "eyJhbGciOiJub25lIn0.eyJleHAiOjF9.";
        String message = ArchimeshAuthHints.describeTokenExpiry(token);
        Assertions.assertTrue(message.contains("expired at"), message);
    }

    @Test
    void tokenExpiryRecognizesMissingExpClaim() {
        String token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbGljZSJ9.";
        String message = ArchimeshAuthHints.describeTokenExpiry(token);
        Assertions.assertTrue(message.contains("no exp"), message);
    }

    @Test
    void tokenIdentityRecognizesSubjectAndRoles() {
        String token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbGljZSIsImdyb3VwcyI6WyJhZG1pbiIsIm1vZGVsX3dyaXRlciJdfQ.";
        String message = ArchimeshAuthHints.describeTokenIdentity(token);
        Assertions.assertTrue(message.contains("alice"), message);
        Assertions.assertTrue(message.contains("admin"), message);
        Assertions.assertTrue(message.contains("model_writer"), message);
    }

    @Test
    void tokenIdentityHandlesMissingClaims() {
        String token = "eyJhbGciOiJub25lIn0.eyJpc3MiOiJ0ZXN0In0.";
        String message = ArchimeshAuthHints.describeTokenIdentity(token);
        Assertions.assertTrue(message.contains("not found"), message);
    }

    @Test
    void preflightBearerHintMentionsExpiryAndRoles() {
        String message = ArchimeshAuthHints.describePreflightAuthHint(true);
        Assertions.assertTrue(message.contains("unexpired"), message);
        Assertions.assertTrue(message.contains("roles"), message);
    }

    @Test
    void http401WithBearerMentionsExpiredOrInvalidToken() {
        String message = ArchimeshAuthHints.describeHttpFailure("Loading model catalog", 401, true);
        Assertions.assertTrue(message.contains("invalid, or expired"), message);
    }

    @Test
    void authRequiredServerErrorMentionsBearerTokenWhenPresent() {
        String message = ArchimeshAuthHints.describeServerError("AUTH_REQUIRED", "Authenticated subject is required.", true, false);
        Assertions.assertTrue(message.contains("bearer token"), message);
    }

    @Test
    void readOnlyServerErrorMentionsHead() {
        String message = ArchimeshAuthHints.describeServerError("MODEL_REFERENCE_READ_ONLY", "read only", false, true);
        Assertions.assertTrue(message.contains("HEAD"), message);
    }
}
