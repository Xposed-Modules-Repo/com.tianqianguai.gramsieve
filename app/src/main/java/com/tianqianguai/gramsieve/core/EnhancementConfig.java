package com.tianqianguai.gramsieve.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional Telegram enhancements kept separate from GramSieve's filtering rules.
 *
 * <p>The stable string keys make saved settings forward-compatible: a newer APK can add a
 * feature without invalidating older JSON, while removed/unknown keys are ignored.</p>
 */
public final class EnhancementConfig {
    public Map<String, Boolean> enabled = new LinkedHashMap<>();
    public Map<String, Boolean> moduleFallbacks = new LinkedHashMap<>();
    public int downloadParallelism = 8;
    public int uploadParallelism = 4;
    public String outgoingPrefix = "";
    public String outgoingSuffix = "";

    public boolean isEnabled(Feature feature) {
        return feature != null && Boolean.TRUE.equals(enabled.get(feature.key));
    }

    public void setEnabled(Feature feature, boolean value) {
        if (feature == null) {
            return;
        }
        if (value) {
            enabled.put(feature.key, true);
        } else {
            enabled.remove(feature.key);
        }
    }

    public boolean isModuleFallbackEnabled(ModuleConflictDetector.KnownModule module) {
        return module != null && moduleFallbacks != null
                && Boolean.TRUE.equals(moduleFallbacks.get(module.name()));
    }

    public void setModuleFallbackEnabled(ModuleConflictDetector.KnownModule module, boolean value) {
        if (module == null) {
            return;
        }
        if (moduleFallbacks == null) {
            moduleFallbacks = new LinkedHashMap<>();
        }
        if (value) {
            moduleFallbacks.put(module.name(), true);
        } else {
            moduleFallbacks.remove(module.name());
        }
    }

    public boolean yieldsToModule(ModuleConflictDetector.ConflictKind kind) {
        if (kind == null) {
            return false;
        }
        for (ModuleConflictDetector.KnownModule module : ModuleConflictDetector.KnownModule.values()) {
            if (isModuleFallbackEnabled(module) && module.has(kind)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEnabledForGramSieve(Feature feature) {
        if (!isEnabled(feature)) {
            return false;
        }
        switch (feature) {
            case DISABLE_TYPING_STATUS:
            case HIDE_PRIVATE_READ_STATUS:
            case HIDE_GROUP_READ_STATUS:
            case HIDE_PHONE_NUMBER:
            case SHOW_EXACT_LAST_SEEN:
                return !yieldsToModule(ModuleConflictDetector.ConflictKind.PRIVACY);
            case HIDE_STORY_VIEW_STATUS:
                return !yieldsToAny(
                        ModuleConflictDetector.ConflictKind.PRIVACY,
                        ModuleConflictDetector.ConflictKind.STORIES
                );
            case DISABLE_PERSONALIZED_ADS:
            case HIDE_SPONSORED_MESSAGES:
                return !yieldsToModule(ModuleConflictDetector.ConflictKind.ADS);
            case ALLOW_COPY:
            case ALLOW_FORWARD:
            case SAVE_VOICE_MESSAGES:
                return !yieldsToModule(ModuleConflictDetector.ConflictKind.SAVE_RESTRICTION);
            case SAVE_SECRET_MEDIA:
                return !yieldsToAny(
                        ModuleConflictDetector.ConflictKind.SECRET_MEDIA,
                        ModuleConflictDetector.ConflictKind.SAVE_RESTRICTION
                );
            case SAVE_STORIES:
            case HIDE_SERVICE_STORIES:
            case HIDE_PREMIUM_STICKER_TAB:
                return !yieldsToModule(ModuleConflictDetector.ConflictKind.STORIES);
            case DOWNLOAD_BOOST:
                return !yieldsToModule(ModuleConflictDetector.ConflictKind.DOWNLOAD_ACCELERATION);
            default:
                return true;
        }
    }

    private boolean yieldsToAny(ModuleConflictDetector.ConflictKind... kinds) {
        if (kinds == null) {
            return false;
        }
        for (ModuleConflictDetector.ConflictKind kind : kinds) {
            if (yieldsToModule(kind)) {
                return true;
            }
        }
        return false;
    }

    public EnhancementConfig sanitize() {
        if (enabled == null) {
            enabled = new LinkedHashMap<>();
        }
        Map<String, Boolean> known = new LinkedHashMap<>();
        for (Feature feature : Feature.values()) {
            if (feature.isAvailableInCurrentBuild() && Boolean.TRUE.equals(enabled.get(feature.key))) {
                known.put(feature.key, true);
            }
        }
        enabled = known;
        if (moduleFallbacks == null) {
            moduleFallbacks = new LinkedHashMap<>();
        }
        Map<String, Boolean> knownFallbacks = new LinkedHashMap<>();
        for (ModuleConflictDetector.KnownModule module : ModuleConflictDetector.KnownModule.values()) {
            if (Boolean.TRUE.equals(moduleFallbacks.get(module.name()))) {
                knownFallbacks.put(module.name(), true);
            }
        }
        moduleFallbacks = knownFallbacks;
        downloadParallelism = clamp(downloadParallelism, 2, 32);
        uploadParallelism = clamp(uploadParallelism, 1, 16);
        outgoingPrefix = normalizeAffix(outgoingPrefix);
        outgoingSuffix = normalizeAffix(outgoingSuffix);
        return this;
    }

    public EnhancementConfig deepCopy() {
        EnhancementConfig copy = new EnhancementConfig();
        if (enabled != null) {
            copy.enabled.putAll(enabled);
        }
        if (moduleFallbacks != null) {
            copy.moduleFallbacks.putAll(moduleFallbacks);
        }
        copy.downloadParallelism = downloadParallelism;
        copy.uploadParallelism = uploadParallelism;
        copy.outgoingPrefix = outgoingPrefix;
        copy.outgoingSuffix = outgoingSuffix;
        return copy;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String normalizeAffix(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    public enum Category {
        PRIVACY,
        MESSAGES,
        MEDIA,
        INTERFACE,
        TRANSFER,
        TOOLS
    }

    public enum Feature {
        DISABLE_TYPING_STATUS("disable_typing_status", Category.PRIVACY),
        HIDE_PRIVATE_READ_STATUS("hide_private_read_status", Category.PRIVACY),
        HIDE_GROUP_READ_STATUS("hide_group_read_status", Category.PRIVACY),
        HIDE_STORY_VIEW_STATUS("hide_story_view_status", Category.PRIVACY),
        HIDE_PHONE_NUMBER("hide_phone_number", Category.PRIVACY),
        DISABLE_PERSONALIZED_ADS("disable_personalized_ads", Category.PRIVACY),
        SHOW_EXACT_LAST_SEEN("show_exact_last_seen", Category.PRIVACY),
        MARK_READ_AFTER_SEND("mark_read_after_send", Category.PRIVACY),

        SHOW_MESSAGE_ID("show_message_id", Category.MESSAGES),
        RELOAD_MESSAGE("reload_message", Category.MESSAGES),
        MESSAGE_AFFIXES("message_affixes", Category.MESSAGES),
        AUTO_SAVE_SENT_MESSAGES("auto_save_sent_messages", Category.MESSAGES),
        EXTENDED_JUMP_TO_MESSAGE("extended_jump_to_message", Category.MESSAGES),
        LOCAL_MESSAGE_DISPLAY("local_message_display", Category.MESSAGES),
        SEND_COMMANDS("send_commands", Category.MESSAGES),

        ALLOW_COPY("allow_copy", Category.MEDIA),
        ALLOW_FORWARD("allow_forward", Category.MEDIA),
        SAVE_VOICE_MESSAGES("save_voice_messages", Category.MEDIA),
        SAVE_SECRET_MEDIA("save_secret_media", Category.MEDIA),
        KEEP_SECRET_MEDIA("keep_secret_media", Category.MEDIA),
        SAVE_STORIES("save_stories", Category.MEDIA),
        KEEP_VIDEO_MUTED("keep_video_muted", Category.MEDIA),
        DISABLE_PREMIUM_STICKER_ANIMATION("disable_premium_sticker_animation", Category.MEDIA),

        HIDE_SPONSORED_MESSAGES("hide_sponsored_messages", Category.INTERFACE),
        HIDE_PINNED_MESSAGE("hide_pinned_message", Category.INTERFACE),
        HIDE_SERVICE_STORIES("hide_service_stories", Category.INTERFACE),
        HIDE_PREMIUM_STICKER_TAB("hide_premium_sticker_tab", Category.INTERFACE),
        HIDE_CONTACTS_TAB("hide_contacts_tab", Category.INTERFACE),
        HIDE_HOME_ACTION_BUTTONS("hide_home_action_buttons", Category.INTERFACE),
        DISABLE_INSTANT_CAMERA("disable_instant_camera", Category.INTERFACE),
        DISABLE_CHAT_SWIPE_BACK("disable_chat_swipe_back", Category.INTERFACE),
        DISABLE_PROFILE_SWIPE_BACK("disable_profile_swipe_back", Category.INTERFACE),
        DISABLE_UPDATE_PROMPT("disable_update_prompt", Category.INTERFACE),
        FORCE_CHAT_BLUR("force_chat_blur", Category.INTERFACE),
        USE_SYSTEM_EMOJI("use_system_emoji", Category.INTERFACE),
        SHOW_ID_IN_PROFILE("show_id_in_profile", Category.INTERFACE),
        SHOW_ID_IN_STATUS_LINE("show_id_in_status_line", Category.INTERFACE),
        COPY_PROFILE_NAME("copy_profile_name", Category.INTERFACE),
        SHOW_FULL_NUMBERS("show_full_numbers", Category.INTERFACE),
        VIEW_TOPIC_AS_MESSAGES("view_topic_as_messages", Category.INTERFACE),
        FORCE_SNOW_ANIMATION("force_snow_animation", Category.INTERFACE),
        HIDE_PROTOCOL_ERRORS("hide_protocol_errors", Category.INTERFACE),

        DOWNLOAD_BOOST("download_boost", Category.TRANSFER),
        UPLOAD_BOOST("upload_boost", Category.TRANSFER),
        KEEP_DOWNLOAD_BUTTON_VISIBLE("keep_download_button_visible", Category.TRANSFER),
        SHOW_DOWNLOAD_SOURCE("show_download_source", Category.TRANSFER),

        EXTENDED_OFFLINE_SEARCH("extended_offline_search", Category.TOOLS),
        LOCAL_GROUP_MEMBER_LIST("local_group_member_list", Category.TOOLS),
        HISTORIC_GROUP_MEMBERS("historic_group_members", Category.TOOLS),
        ACCOUNT_CREATION_ESTIMATE("account_creation_estimate", Category.TOOLS),
        CUSTOM_CALENDAR("custom_calendar", Category.TOOLS),
        OPEN_DIALOG_BY_ID("open_dialog_by_id", Category.TOOLS),
        DATABASE_CORRUPTION_WARNING("database_corruption_warning", Category.TOOLS),
        TELEGRAM_DEBUG_MODE("telegram_debug_mode", Category.TOOLS),
        NETWORK_LOG_CONTROL("network_log_control", Category.TOOLS);

        public final String key;
        public final Category category;

        Feature(String key, Category category) {
            this.key = key;
            this.category = category;
        }

        /** Prevents a saved switch from promising behavior that has no current runtime owner. */
        public boolean isAvailableInCurrentBuild() {
            switch (this) {
                case MARK_READ_AFTER_SEND:
                case AUTO_SAVE_SENT_MESSAGES:
                case EXTENDED_JUMP_TO_MESSAGE:
                case LOCAL_MESSAGE_DISPLAY:
                case SEND_COMMANDS:
                case KEEP_SECRET_MEDIA:
                case VIEW_TOPIC_AS_MESSAGES:
                case SHOW_DOWNLOAD_SOURCE:
                case EXTENDED_OFFLINE_SEARCH:
                case LOCAL_GROUP_MEMBER_LIST:
                case HISTORIC_GROUP_MEMBERS:
                case ACCOUNT_CREATION_ESTIMATE:
                case CUSTOM_CALENDAR:
                case OPEN_DIALOG_BY_ID:
                case DATABASE_CORRUPTION_WARNING:
                    return false;
                default:
                    return true;
            }
        }
    }
}
