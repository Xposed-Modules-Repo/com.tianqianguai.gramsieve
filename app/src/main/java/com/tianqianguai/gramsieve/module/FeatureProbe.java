package com.tianqianguai.gramsieve.module;

import android.view.View;
import com.tianqianguai.gramsieve.core.EnhancementConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only descriptions of the targets the enhancement installer actually uses. */
final class FeatureProbe {
    static final class Spec {
        final String className;
        final String methods;
        final String returns;
        final String[] fields;
        final String fieldKind;

        Spec(String className, String methods, String returns, String fieldKind, String... fields) {
            this.className = className;
            this.methods = methods;
            this.returns = returns;
            this.fieldKind = fieldKind;
            this.fields = fields;
        }
    }

    static Map<String, Object> inspect(EnhancementConfig.Feature feature, ClassLoader loader,
                                       Set<Method> registered, Map<Method, Long> calls, Map<Method, String> owners) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", feature.key);
        result.put("behaviorVerified", false);
        result.put("note", "Target presence, registration and invocation counts do not prove the user-visible behavior.");
        if (!feature.isAvailableInCurrentBuild()) {
            result.put("status", "unavailable_in_build");
            return result;
        }
        Spec spec = spec(feature);
        if (spec == null) {
            result.put("status", "external_owner");
            result.put("note", "Use the existing dedicated UI CLI for this feature; it is not owned by EnhancementHookInstaller.");
            return result;
        }
        try {
            Class<?> type = Class.forName(spec.className, false, loader);
            result.putAll(inspectTargets(type, spec, feature, registered, calls, owners));
        } catch (ClassNotFoundException | LinkageError failure) {
            result.put("className", spec.className);
            result.put("status", "class_missing");
            result.put("error", failure.getClass().getSimpleName());
        }
        return result;
    }

    static Map<String, Object> inspectTargets(Class<?> type, Spec spec,
            EnhancementConfig.Feature feature, Set<Method> registered, Map<Method, Long> calls, Map<Method, String> owners) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", type.getName());
        List<Map<String, Object>> methods = new ArrayList<>();
        int registeredCount = 0;
        int ownedCount = 0;
        if (!spec.methods.isEmpty()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().matches(spec.methods)
                        || (spec.returns != null && !method.getReturnType().getName().equals(spec.returns))
                        || !matchesParameters(feature, method)) {
                    continue;
                }
                boolean hooked = registered.contains(method);
                if (hooked) {
                    registeredCount++;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("signature", method.toGenericString());
                item.put("registered", hooked);
                String registeredBy = owners.get(method);
                boolean owned = hooked && owner(feature).equals(registeredBy);
                if (owned) {
                    ownedCount++;
                }
                item.put("registeredBy", registeredBy);
                item.put("expectedHandler", owner(feature));
                item.put("handlerMatches", owned);
                item.put("invocations", calls.getOrDefault(method, 0L));
                methods.add(item);
            }
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        int compatibleFields = 0;
        for (String name : spec.fields) {
            Field field = field(type, name);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("present", field != null);
            item.put("expected", spec.fieldKind);
            if (field != null) {
                item.put("declaredType", field.getType().getName());
                boolean compatible = compatible(field.getType(), spec.fieldKind);
                item.put("compatible", compatible);
                if (compatible) {
                    compatibleFields++;
                }
            }
            fields.add(item);
        }
        result.put("methods", methods);
        result.put("declaredMethodCount", methods.size());
        result.put("registeredMethodCount", registeredCount);
        result.put("featureHandlerCount", ownedCount);
        result.put("fields", fields);
        String status = !spec.methods.isEmpty() && methods.isEmpty() ? "method_missing"
                : spec.fields.length > 0 && compatibleFields == 0 ? "field_missing_or_incompatible"
                : spec.methods.isEmpty() ? "static_flags_only"
                : registeredCount == 0 ? "not_registered"
                : ownedCount == 0 ? "registered_for_other_handler" : "registered_behavior_unverified";
        result.put("status", status);
        return result;
    }

    private static String owner(EnhancementConfig.Feature feature) {
        switch (feature) {
            case DISABLE_TYPING_STATUS: case HIDE_PRIVATE_READ_STATUS: case HIDE_GROUP_READ_STATUS:
            case HIDE_STORY_VIEW_STATUS: case DISABLE_PERSONALIZED_ADS: case MESSAGE_AFFIXES:
                return "installNetworkPolicyHooks";
            case SHOW_MESSAGE_ID: return "installMessageIdHook";
            case ALLOW_COPY: case SAVE_VOICE_MESSAGES: case SAVE_STORIES: return "hookBooleanMethodsAny";
            case ALLOW_FORWARD: case DISABLE_PREMIUM_STICKER_ANIMATION: return "hookBooleanMethods";
            case SAVE_SECRET_MEDIA: return "hookSecretMediaSavePolicy";
            case KEEP_VIDEO_MUTED: return "hookVideoMute";
            case HIDE_SPONSORED_MESSAGES: return "hookSponsoredMessageLoaders";
            case HIDE_PINNED_MESSAGE: return "hookPinnedMessage";
            case HIDE_STORY_BAR: return "hookDialogsStoryVisibility";
            case HIDE_SERVICE_STORIES: return "hookServiceStories";
            case HIDE_PREMIUM_STICKER_TAB: return "hookPremiumStickerTab";
            case HIDE_CONTACTS_TAB: case HIDE_HOME_ACTION_BUTTONS: return "hookHomeActions";
            case HIDE_PHONE_NUMBER: case SHOW_ID_IN_PROFILE: case COPY_PROFILE_NAME: return "hookProfilePrivacy";
            case SHOW_EXACT_LAST_SEEN: return "hookExactLastSeen";
            case SHOW_ID_IN_STATUS_LINE: return "hookStatusLineId";
            case DISABLE_INSTANT_CAMERA: return "hookInstantCamera";
            case DISABLE_CHAT_SWIPE_BACK: case DISABLE_PROFILE_SWIPE_BACK: return "hookSwipeBack";
            case DISABLE_UPDATE_PROMPT: return "hookUpdatePrompt";
            case FORCE_CHAT_BLUR: return "hookBlurAvailability";
            case FORCE_SNOW_ANIMATION: return "hookHolidayAnimation";
            case USE_SYSTEM_EMOJI: return "hookSystemEmoji";
            case SHOW_FULL_NUMBERS: return "hookExactNumberFormatting";
            case HIDE_PROTOCOL_ERRORS: return "hookProtocolErrors";
            case DOWNLOAD_BOOST: case UPLOAD_BOOST: return "hookTransferOperation";
            default: return "";
        }
    }

    private static boolean matchesParameters(EnhancementConfig.Feature feature, Method method) {
        if (feature == EnhancementConfig.Feature.HIDE_SERVICE_STORIES) {
            return method.getParameterCount() > 0;
        }
        if (feature == EnhancementConfig.Feature.SHOW_EXACT_LAST_SEEN) {
            return method.getParameterCount() > 0
                    && (method.getParameterTypes()[0] == long.class || method.getParameterTypes()[0] == int.class);
        }
        if (feature == EnhancementConfig.Feature.SHOW_MESSAGE_ID) {
            return method.getParameterCount() == 1;
        }
        if (feature == EnhancementConfig.Feature.KEEP_VIDEO_MUTED) {
            return method.getParameterCount() == 1 && method.getParameterTypes()[0] == boolean.class;
        }
        return true;
    }

    static boolean compatible(Class<?> type, String kind) {
        if ("view".equals(kind)) {
            return View.class.isAssignableFrom(type);
        }
        if ("int".equals(kind)) {
            return type == int.class;
        }
        if ("boolean".equals(kind)) {
            return type == boolean.class;
        }
        return true;
    }

    static Field field(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Lookup only; never invoke a Telegram method or change a field.
            }
        }
        return null;
    }

    private static Spec s(String cls, String methods, String returns, String kind, String... fields) {
        return new Spec("org.telegram." + cls, methods, returns, kind, fields);
    }

    static Spec spec(EnhancementConfig.Feature feature) {
        switch (feature) {
            case DISABLE_TYPING_STATUS:
            case HIDE_PRIVATE_READ_STATUS:
            case HIDE_GROUP_READ_STATUS:
            case HIDE_STORY_VIEW_STATUS:
            case DISABLE_PERSONALIZED_ADS:
            case MESSAGE_AFFIXES:
                return s("tgnet.ConnectionsManager", "sendRequest|sendRequestInternal", null, "any");
            case SHOW_MESSAGE_ID:
                return s("ui.Cells.ChatMessageCell", "measureTime", "void", "any", "currentTimeString", "timeTextWidth", "timeWidth");
            case ALLOW_COPY:
            case SAVE_VOICE_MESSAGES:
            case SAVE_STORIES:
                return s("messenger.MessageObject", "canSaveMedia", "boolean", "any");
            case SAVE_SECRET_MEDIA:
                return s("messenger.SaveToGallerySettingsHelper", "needSave", "boolean", "any");
            case ALLOW_FORWARD:
                return s("messenger.MessageObject", "canForwardMessage", "boolean", "any");
            case DISABLE_PREMIUM_STICKER_ANIMATION:
                return s("messenger.MessageObject", "isPremiumSticker", "boolean", "any");
            case KEEP_VIDEO_MUTED:
                return s("ui.Components.VideoPlayer", "setMute", "void", "any");
            case HIDE_SPONSORED_MESSAGES:
                return s("messenger.MessagesController", ".*[Ss]ponsoredMessages.*", null, "any");
            case HIDE_PINNED_MESSAGE:
                return s("ui.ChatActivity", "updatePinnedMessageView.*", null, "view", "pinnedMessageView", "pinnedMessageViewAnimator");
            case HIDE_STORY_BAR:
                return s("ui.DialogsActivity", "updateStoriesVisibility", "void", "view", "dialogStoriesCell");
            case HIDE_SERVICE_STORIES:
                return s("ui.Stories.StoriesController", "hasStories", "boolean", "any");
            case HIDE_PREMIUM_STICKER_TAB:
                return s("ui.Components.EmojiView", "updateTabs|updateStickerTabs", null, "view", "premiumTab", "premiumButton");
            case HIDE_CONTACTS_TAB:
                return s("ui.DialogsActivity", "createView|onResume", null, "view", "contactsItem", "contactsButton", "contactsTab");
            case HIDE_HOME_ACTION_BUTTONS:
                return s("ui.DialogsActivity", "createView|onResume", null, "view", "floatingButton", "floatingButtonContainer", "floatingButton2");
            case HIDE_PHONE_NUMBER:
                return s("ui.ProfileActivity", "updateProfileData|updateRowsIds", null, "view", "phoneTextView", "phoneRow");
            case SHOW_ID_IN_PROFILE:
                return s("ui.ProfileActivity", "updateProfileData|updateRowsIds", null, "view", "onlineTextView", "nameTextView");
            case COPY_PROFILE_NAME:
                return s("ui.ProfileActivity", "updateProfileData|updateRowsIds", null, "view", "nameTextView");
            case SHOW_EXACT_LAST_SEEN:
                return s("messenger.LocaleController", "formatDateOnline", "java.lang.String", "any");
            case SHOW_ID_IN_STATUS_LINE:
                return s("ui.Components.ChatAvatarContainer", "updateSubtitle", null, "view", "subtitleTextView");
            case DISABLE_INSTANT_CAMERA:
                return s("ui.Components.ChatActivityEnterView", "openCamera|onCameraPressed|showCamera", null, "any");
            case DISABLE_CHAT_SWIPE_BACK:
                return s("ui.ChatActivity", "isSwipeBackEnabled|canBeginSlide", "boolean", "any");
            case DISABLE_PROFILE_SWIPE_BACK:
                return s("ui.ProfileActivity", "isSwipeBackEnabled|canBeginSlide", "boolean", "any");
            case DISABLE_UPDATE_PROMPT:
                return s("messenger.SharedConfig", ".*[Aa]ppUpdate.*", null, "any");
            case FORCE_CHAT_BLUR:
                return s("messenger.SharedConfig", "(?i).*blur.*", "boolean", "any");
            case FORCE_SNOW_ANIMATION:
                return s("ui.ActionBar.Theme", "(?i).*(holiday|snow).*", "boolean", "any");
            case USE_SYSTEM_EMOJI:
                return s("messenger.Emoji", "replaceEmoji", "java.lang.CharSequence", "any");
            case SHOW_FULL_NUMBERS:
                return s("messenger.LocaleController", "formatShortNumber", "java.lang.String", "any");
            case HIDE_PROTOCOL_ERRORS:
                return s("ui.Components.AlertsCreator", "processError", null, "any");
            case DOWNLOAD_BOOST:
                return s("messenger.FileLoadOperation", "start|startDownloadRequest", null, "int", "maxDownloadRequests", "maxRequestsCount", "currentMaxDownloadRequests");
            case UPLOAD_BOOST:
                return s("messenger.FileUploadOperation", "start|startDownloadRequest", null, "int", "maxRequestsCount", "maxUploadRequests");
            case TELEGRAM_DEBUG_MODE:
                return s("messenger.BuildVars", "", null, "boolean", "DEBUG_VERSION");
            case NETWORK_LOG_CONTROL:
                return s("messenger.BuildVars", "", null, "boolean", "LOGS_ENABLED");
            default:
                return null;
        }
    }
}
