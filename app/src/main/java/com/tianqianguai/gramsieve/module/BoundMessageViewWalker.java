package com.tianqianguai.gramsieve.module;

import android.view.View;
import android.view.ViewGroup;

final class BoundMessageViewWalker {
    private BoundMessageViewWalker() {
    }

    static boolean visit(Object rootCandidate, MessageViewVisitor visitor) {
        if (!(rootCandidate instanceof View) || visitor == null) {
            return false;
        }
        return visitView((View) rootCandidate, visitor);
    }

    private static boolean visitView(View view, MessageViewVisitor visitor) {
        Object messageObject = resolveMessageObject(view);
        if (messageObject != null) {
            visitor.visit(view, messageObject);
            return true;
        }
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        boolean found = false;
        for (int i = 0; i < group.getChildCount(); i++) {
            found |= visitView(group.getChildAt(i), visitor);
        }
        return found;
    }

    private static Object resolveMessageObject(View view) {
        Object currentMessageObject = Reflect.field(view, "currentMessageObject");
        if (currentMessageObject != null) {
            return currentMessageObject;
        }
        Object messageObject = Reflect.field(view, "messageObject");
        if (messageObject != null) {
            return messageObject;
        }
        return Reflect.invokeIfExists(view, "getMessageObject", new Class<?>[0]);
    }

    interface MessageViewVisitor {
        void visit(View view, Object messageObject);
    }
}
