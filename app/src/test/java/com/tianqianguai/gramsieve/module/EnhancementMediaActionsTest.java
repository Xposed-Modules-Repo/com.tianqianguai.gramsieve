package com.tianqianguai.gramsieve.module;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnhancementMediaActionsTest {
    @Test
    public void copyingMediaUsesRealTextOrCaptionNotTheSyntheticAlbumLabel() {
        Message message = new Message();
        message.messageOwner.media = new Object();
        message.messageText = "相册";
        assertEquals("", EnhancementMediaActions.copyText(message));
        message.caption = "media caption";
        assertEquals("media caption", EnhancementMediaActions.copyText(message));
        message.messageOwner.message = "original text";
        assertEquals("original text", EnhancementMediaActions.copyText(message));
    }

    @Test
    public void copyingNormalTextKeepsRenderedFallbackWithoutMutatingMessage() {
        Message message = new Message();
        message.messageText = "normal text";
        assertEquals("normal text", EnhancementMediaActions.copyText(message));
        assertEquals("", message.messageOwner.message);
        assertNull(message.messageOwner.media);
    }

    static class Message {
        Owner messageOwner = new Owner();
        String messageText = "";
        String caption = "";
    }
    static class Owner { String message = ""; Object media; }
}
