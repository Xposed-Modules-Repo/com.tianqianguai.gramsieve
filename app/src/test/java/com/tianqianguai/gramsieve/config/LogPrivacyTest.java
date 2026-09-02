package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogPrivacyTest {
    @Test
    public void debugFieldKeepsBoundedContent() {
        assertEquals(
                "text=\"hello world\"",
                LogPrivacy.fieldForBuild("text", " hello\nworld ", true)
        );
        assertTrue(LogPrivacy.fieldForBuild("text", "x".repeat(60), true).endsWith("...\""));
    }

    @Test
    public void releaseFieldKeepsPresenceAndLengthOnly() {
        String field = LogPrivacy.fieldForBuild("text", " private message ", false);

        assertEquals("textPresent=true textChars=15", field);
        assertFalse(field.contains("private"));
        assertEquals("captionPresent=false captionChars=0",
                LogPrivacy.fieldForBuild("caption", "", false));
    }

    @Test
    public void releaseSanitizerRedactsLegacyQuotedFields() {
        String source = "DecisionProbe chat=\"private group\" sender=\"alice\" "
                + "dialog=42 text=\"secret body\"";

        String sanitized = LogPrivacy.sanitizeMessageForBuild(source, false);

        assertFalse(sanitized.contains("private group"));
        assertFalse(sanitized.contains("alice"));
        assertFalse(sanitized.contains("secret body"));
        assertTrue(sanitized.contains("chatPresent=true chatChars=13"));
        assertTrue(sanitized.contains("senderPresent=true senderChars=5"));
        assertTrue(sanitized.contains("textPresent=true textChars=11"));
        assertTrue(sanitized.contains("dialog=42"));
    }

    @Test
    public void releaseSanitizerHandlesQuotesInsideLegacyContent() {
        String source = "DecisionProbe text=\"secret \"quoted\" body\" dialog=42";

        String sanitized = LogPrivacy.sanitizeMessageForBuild(source, false);

        assertFalse(sanitized.contains("secret"));
        assertFalse(sanitized.contains("quoted"));
        assertFalse(sanitized.contains("body"));
        assertTrue(sanitized.contains("textPresent=true"));
        assertTrue(sanitized.contains("dialog=42"));
    }

    @Test
    public void debugSanitizerDoesNotChangeMessage() {
        String source = "text=\"debug body\"";

        assertEquals(source, LogPrivacy.sanitizeMessageForBuild(source, true));
    }
}
