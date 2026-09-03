package com.tianqianguai.gramsieve.module;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.view.View;
import android.widget.Toast;

import com.tianqianguai.gramsieve.config.ModuleLogger;
import com.tianqianguai.gramsieve.config.XposedConfigProvider;
import com.tianqianguai.gramsieve.core.EnhancementConfig;
import com.tianqianguai.gramsieve.core.FilterConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Date;
import java.util.Set;
import java.util.WeakHashMap;
import java.text.SimpleDateFormat;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Clean-room, best-effort Telegram enhancements that stay independent of the filter hooks. */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
final class EnhancementHookInstaller {
    private static final String TAG = "GramSieve";
    private static final long CONFIG_SNAPSHOT_MS = 1500L;
    private static final String[] READ_REQUESTS = {
            "TL_messages_readHistory",
            "TL_messages_readEncryptedHistory",
            "TL_messages_readDiscussion",
            "TL_messages_readSavedHistory",
            "TL_messages_readMessageContents",
            "TL_channels_readMessageContents",
            "TL_channels_readHistory"
    };
    private static final String[] TYPING_REQUESTS = {
            "TL_messages_setTyping",
            "TL_messages_setEncryptedTyping"
    };
    private static final String[] STORY_VIEW_REQUESTS = {
            "TL_stories_readStories",
            "TL_stories_incrementStoryViews"
    };
    private static final String[] SEND_REQUESTS = {
            "TL_messages_sendMessage",
            "TL_messages_sendMedia",
            "TL_messages_sendMultiMedia"
    };

    private final XposedModule module;
    private final Set<Method> hookedMethods = Collections.synchronizedSet(new HashSet<>());
    private final Set<Object> affixedRequests = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>())
    );
    private final Object configLock = new Object();
    private XposedConfigProvider configProvider;
    private volatile EnhancementConfig cachedEnhancements = new EnhancementConfig();
    private volatile long lastConfigRefreshAt = -CONFIG_SNAPSHOT_MS;
    private volatile Context applicationContext;
    private volatile boolean active = true;

    EnhancementHookInstaller(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader classLoader, XposedConfigProvider configProvider) {
        active = true;
        this.configProvider = configProvider;
        this.lastConfigRefreshAt = -CONFIG_SNAPSHOT_MS;
        installNetworkPolicyHooks(classLoader);
        installMessageIdHook(classLoader);
        installContentPolicyHooks(classLoader);
        installInterfaceHooks(classLoader);
        installTransferHooks(classLoader);
        applyStartupFlags(classLoader);
    }

    void prepareForHotReload() {
        active = false;
        hookedMethods.clear();
        affixedRequests.clear();
        configProvider = null;
        applicationContext = null;
        cachedEnhancements = new EnhancementConfig();
        lastConfigRefreshAt = -CONFIG_SNAPSHOT_MS;
    }

    private void installNetworkPolicyHooks(ClassLoader classLoader) {
        Class<?> tlObject = load(classLoader, "org.telegram.tgnet.TLObject");
        Class<?> connectionsManager = load(classLoader, "org.telegram.tgnet.ConnectionsManager");
        if (tlObject == null || connectionsManager == null) {
            warn("Enhancements: Telegram network classes unavailable");
            return;
        }
        int count = 0;
        for (Method method : connectionsManager.getDeclaredMethods()) {
            String name = method.getName();
            Class<?>[] parameters = method.getParameterTypes();
            if (!("sendRequest".equals(name) || "sendRequestInternal".equals(name))
                    || parameters.length == 0
                    || !tlObject.isAssignableFrom(parameters[0])) {
                continue;
            }
            hook(method, chain -> {
                List<Object> args = chain.getArgs();
                Object request = args.isEmpty() ? null : args.get(0);
                EnhancementConfig config = config();
                if (request != null) {
                    applyMessageAffixes(request, config);
                    if (shouldBlockRequest(request, config)) {
                        debug("Blocked privacy request " + request.getClass().getSimpleName());
                        return defaultValue(method.getReturnType());
                    }
                }
                return chain.proceed();
            });
            count++;
        }
        info("Enhancements: installed " + count + " network policy hooks");
    }

    private boolean shouldBlockRequest(Object request, EnhancementConfig config) {
        String name = request.getClass().getName();
        if (config.isEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS)
                && containsAny(name, TYPING_REQUESTS)) {
            return true;
        }
        if (config.isEnabled(EnhancementConfig.Feature.HIDE_STORY_VIEW_STATUS)
                && containsAny(name, STORY_VIEW_REQUESTS)) {
            return true;
        }
        if (containsAny(name, READ_REQUESTS)) {
            boolean group = name.contains("TL_channels_") || peerLooksLikeGroup(request);
            return group
                    ? config.isEnabled(EnhancementConfig.Feature.HIDE_GROUP_READ_STATUS)
                    : config.isEnabled(EnhancementConfig.Feature.HIDE_PRIVATE_READ_STATUS);
        }
        if (config.isEnabled(EnhancementConfig.Feature.HIDE_SPONSORED_MESSAGES)
                && (name.contains("getSponsoredMessages") || name.contains("viewSponsoredMessage"))) {
            return true;
        }
        return config.isEnabled(EnhancementConfig.Feature.DISABLE_PERSONALIZED_ADS)
                && (name.contains("saveAppLog") || name.contains("saveRecentMeUrls"));
    }

    private void applyMessageAffixes(Object request, EnhancementConfig config) {
        if (!config.isEnabled(EnhancementConfig.Feature.MESSAGE_AFFIXES)
                || !containsAny(request.getClass().getName(), SEND_REQUESTS)
                || (!affixedRequests.add(request))) {
            return;
        }
        Field messageField = findField(request.getClass(), "message");
        if (messageField == null || messageField.getType() != String.class) {
            return;
        }
        try {
            String original = (String) messageField.get(request);
            if (original == null || original.isBlank()) {
                return;
            }
            String prefix = config.outgoingPrefix;
            String suffix = config.outgoingSuffix;
            messageField.set(request,
                    (prefix.isBlank() ? "" : prefix + " ")
                            + original
                            + (suffix.isBlank() ? "" : " " + suffix));
        } catch (IllegalAccessException throwable) {
            warn("Enhancements: could not apply message affixes");
        }
    }

    private void installMessageIdHook(ClassLoader classLoader) {
        Class<?> cellClass = load(classLoader, "org.telegram.ui.Cells.ChatMessageCell");
        Class<?> messageObjectClass = load(classLoader, "org.telegram.messenger.MessageObject");
        if (cellClass == null || messageObjectClass == null) {
            return;
        }
        Method target = null;
        for (Method method : cellClass.getDeclaredMethods()) {
            if ("measureTime".equals(method.getName())
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(messageObjectClass)) {
                target = method;
                break;
            }
        }
        if (target == null) {
            warn("Enhancements: message ID target unavailable");
            return;
        }
        hook(target, chain -> {
            Object result = chain.proceed();
            if (!enabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID) || chain.getArgs().isEmpty()) {
                return result;
            }
            appendMessageId(chain.getThisObject(), chain.getArgs().get(0), classLoader);
            return result;
        });
        info("Enhancements: installed message ID hook");
    }

    private void appendMessageId(Object cell, Object messageObject, ClassLoader classLoader) {
        Object owner = Reflect.field(messageObject, "messageOwner");
        int messageId = Reflect.asInt(Reflect.field(owner, "id"), 0);
        CharSequence current = asCharSequence(Reflect.field(cell, "currentTimeString"));
        if (messageId == 0 || current == null) {
            return;
        }
        String prefix = "#" + messageId + "  ";
        if (current.toString().startsWith(prefix)) {
            return;
        }
        Reflect.setField(cell, "currentTimeString", new SpannableStringBuilder(prefix).append(current));
        TextPaint paint = resolveTimePaint(classLoader);
        int extra = paint == null ? prefix.length() * 8 : (int) Math.ceil(paint.measureText(prefix));
        addToIntField(cell, "timeTextWidth", extra);
        addToIntField(cell, "timeWidth", extra);
    }

    private TextPaint resolveTimePaint(ClassLoader classLoader) {
        Class<?> theme = load(classLoader, "org.telegram.ui.ActionBar.Theme");
        Object value = theme == null ? null : Reflect.staticField(theme, "chat_timePaint");
        return value instanceof TextPaint ? (TextPaint) value : null;
    }

    private void installContentPolicyHooks(ClassLoader classLoader) {
        Class<?> messageObject = load(classLoader, "org.telegram.messenger.MessageObject");
        if (messageObject == null) {
            return;
        }
        hookBooleanMethods(messageObject, "canForwardMessage", EnhancementConfig.Feature.ALLOW_FORWARD, true);
        hookBooleanMethodsAny(
                messageObject,
                "canSaveMedia",
                true,
                EnhancementConfig.Feature.ALLOW_COPY,
                EnhancementConfig.Feature.SAVE_VOICE_MESSAGES,
                EnhancementConfig.Feature.SAVE_SECRET_MEDIA,
                EnhancementConfig.Feature.SAVE_STORIES
        );
        hookBooleanMethods(messageObject, "isPremiumSticker", EnhancementConfig.Feature.DISABLE_PREMIUM_STICKER_ANIMATION, false);
    }

    private void installInterfaceHooks(ClassLoader classLoader) {
        hookPinnedMessage(classLoader);
        hookSponsoredMessageLoaders(classLoader);
        hookSwipeBack(classLoader, "org.telegram.ui.ChatActivity", EnhancementConfig.Feature.DISABLE_CHAT_SWIPE_BACK);
        hookSwipeBack(classLoader, "org.telegram.ui.ProfileActivity", EnhancementConfig.Feature.DISABLE_PROFILE_SWIPE_BACK);
        hookInstantCamera(classLoader);
        hookUpdatePrompt(classLoader);
        hookExactNumberFormatting(classLoader);
        hookVideoMute(classLoader);
        hookProfilePrivacy(classLoader);
        hookExactLastSeen(classLoader);
        hookStatusLineId(classLoader);
        hookSystemEmoji(classLoader);
        hookBlurAvailability(classLoader);
        hookHolidayAnimation(classLoader);
        hookProtocolErrors(classLoader);
        hookHomeActions(classLoader);
        hookPremiumStickerTab(classLoader);
        hookServiceStories(classLoader);
    }

    private void hookPinnedMessage(ClassLoader classLoader) {
        Class<?> chatActivity = load(classLoader, "org.telegram.ui.ChatActivity");
        if (chatActivity == null) {
            return;
        }
        for (Method method : chatActivity.getDeclaredMethods()) {
            if (!method.getName().startsWith("updatePinnedMessageView")) {
                continue;
            }
            hook(method, chain -> {
                Object result = chain.proceed();
                if (enabled(EnhancementConfig.Feature.HIDE_PINNED_MESSAGE)) {
                    hideViewField(chain.getThisObject(), "pinnedMessageView");
                    hideViewField(chain.getThisObject(), "pinnedMessageViewAnimator");
                }
                return result;
            });
        }
    }

    private void hookSponsoredMessageLoaders(ClassLoader classLoader) {
        Class<?> controller = load(classLoader, "org.telegram.messenger.MessagesController");
        if (controller == null) {
            return;
        }
        for (Method method : controller.getDeclaredMethods()) {
            String name = method.getName();
            if (!(name.contains("SponsoredMessages") || name.contains("sponsoredMessages"))) {
                continue;
            }
            hook(method, chain -> enabled(EnhancementConfig.Feature.HIDE_SPONSORED_MESSAGES)
                    ? emptyValue(method.getReturnType())
                    : chain.proceed());
        }
    }

    private void hookSwipeBack(
            ClassLoader classLoader,
            String className,
            EnhancementConfig.Feature feature
    ) {
        Class<?> type = load(classLoader, className);
        if (type == null) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (("isSwipeBackEnabled".equals(method.getName())
                    || "canBeginSlide".equals(method.getName()))
                    && method.getReturnType() == boolean.class) {
                hook(method, chain -> enabled(feature) ? false : chain.proceed());
            }
        }
    }

    private void hookInstantCamera(ClassLoader classLoader) {
        Class<?> enterView = load(classLoader, "org.telegram.ui.Components.ChatActivityEnterView");
        if (enterView == null) {
            return;
        }
        for (Method method : enterView.getDeclaredMethods()) {
            String name = method.getName();
            if (!("openCamera".equals(name) || "onCameraPressed".equals(name) || "showCamera".equals(name))) {
                continue;
            }
            hook(method, chain -> enabled(EnhancementConfig.Feature.DISABLE_INSTANT_CAMERA)
                    ? defaultValue(method.getReturnType())
                    : chain.proceed());
        }
    }

    private void hookUpdatePrompt(ClassLoader classLoader) {
        Class<?> sharedConfig = load(classLoader, "org.telegram.messenger.SharedConfig");
        if (sharedConfig == null) {
            return;
        }
        for (Method method : sharedConfig.getDeclaredMethods()) {
            String name = method.getName();
            if (!(name.contains("AppUpdate") || name.contains("appUpdate"))) {
                continue;
            }
            hook(method, chain -> enabled(EnhancementConfig.Feature.DISABLE_UPDATE_PROMPT)
                    ? defaultValue(method.getReturnType())
                    : chain.proceed());
        }
    }

    private void hookExactNumberFormatting(ClassLoader classLoader) {
        Class<?> localeController = load(classLoader, "org.telegram.messenger.LocaleController");
        if (localeController == null) {
            return;
        }
        for (Method method : localeController.getDeclaredMethods()) {
            if (!"formatShortNumber".equals(method.getName()) || method.getReturnType() != String.class) {
                continue;
            }
            hook(method, chain -> {
                if (!enabled(EnhancementConfig.Feature.SHOW_FULL_NUMBERS) || chain.getArgs().isEmpty()) {
                    return chain.proceed();
                }
                Object value = chain.getArgs().get(0);
                return value instanceof Number ? String.valueOf(((Number) value).longValue()) : chain.proceed();
            });
        }
    }

    private void hookVideoMute(ClassLoader classLoader) {
        Class<?> player = load(classLoader, "org.telegram.ui.Components.VideoPlayer");
        if (player == null) {
            return;
        }
        for (Method method : player.getDeclaredMethods()) {
            if (!"setMute".equals(method.getName())
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != boolean.class) {
                continue;
            }
            hook(method, chain -> {
                if (enabled(EnhancementConfig.Feature.KEEP_VIDEO_MUTED)) {
                    chain.getArgs().set(0, true);
                }
                return chain.proceed();
            });
        }
    }

    private void hookProfilePrivacy(ClassLoader classLoader) {
        Class<?> profile = load(classLoader, "org.telegram.ui.ProfileActivity");
        if (profile == null) {
            return;
        }
        for (Method method : profile.getDeclaredMethods()) {
            if (!("updateProfileData".equals(method.getName()) || "updateRowsIds".equals(method.getName()))) {
                continue;
            }
            hook(method, chain -> {
                Object result = chain.proceed();
                if (enabled(EnhancementConfig.Feature.HIDE_PHONE_NUMBER)) {
                    hideViewField(chain.getThisObject(), "phoneTextView");
                    hideViewField(chain.getThisObject(), "phoneRow");
                }
                if (enabled(EnhancementConfig.Feature.SHOW_ID_IN_PROFILE)) {
                    appendProfileId(chain.getThisObject());
                }
                if (enabled(EnhancementConfig.Feature.COPY_PROFILE_NAME)) {
                    enableProfileNameCopy(chain.getThisObject());
                }
                return result;
            });
        }
    }

    private void appendProfileId(Object profile) {
        long id = Reflect.asLong(Reflect.field(profile, "userId"), 0L);
        if (id == 0L) {
            id = Reflect.asLong(Reflect.field(profile, "chatId"), 0L);
        }
        if (id == 0L) {
            Object user = Reflect.field(profile, "userInfo");
            id = Reflect.asLong(Reflect.field(user, "id"), 0L);
        }
        Object textView = Reflect.field(profile, "onlineTextView");
        if (textView == null) {
            textView = Reflect.field(profile, "nameTextView");
        }
        if (id == 0L || textView == null) {
            return;
        }
        Object raw = Reflect.invokeIfExists(textView, "getText", new Class<?>[0]);
        String text = Reflect.asString(raw);
        String suffix = "\nID: " + id;
        if (!text.contains(suffix)) {
            Reflect.invokeIfExists(textView, "setMaxLines", new Class<?>[]{int.class}, 2);
            Reflect.invokeIfExists(textView, "setText", new Class<?>[]{CharSequence.class}, text + suffix);
        }
    }

    private void enableProfileNameCopy(Object profile) {
        Object nameView = Reflect.field(profile, "nameTextView");
        if (!(nameView instanceof View)) {
            return;
        }
        View view = (View) nameView;
        view.setLongClickable(true);
        view.setOnLongClickListener(clicked -> {
            String name = Reflect.asString(Reflect.invokeIfExists(nameView, "getText", new Class<?>[0])).trim();
            if (name.isBlank()) {
                return false;
            }
            ClipboardManager clipboard = (ClipboardManager) clicked.getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return false;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Telegram profile name", name));
            Toast.makeText(
                    clicked.getContext(),
                    isChinese(clicked.getContext()) ? "名称已复制" : "Name copied",
                    Toast.LENGTH_SHORT
            ).show();
            return true;
        });
    }

    private void hookExactLastSeen(ClassLoader classLoader) {
        Class<?> localeController = load(classLoader, "org.telegram.messenger.LocaleController");
        if (localeController == null) {
            return;
        }
        for (Method method : localeController.getDeclaredMethods()) {
            if (!"formatDateOnline".equals(method.getName())
                    || method.getReturnType() != String.class
                    || method.getParameterCount() == 0
                    || !(method.getParameterTypes()[0] == long.class || method.getParameterTypes()[0] == int.class)) {
                continue;
            }
            hook(method, chain -> {
                if (!enabled(EnhancementConfig.Feature.SHOW_EXACT_LAST_SEEN) || chain.getArgs().isEmpty()) {
                    return chain.proceed();
                }
                Object raw = chain.getArgs().get(0);
                if (!(raw instanceof Number)) {
                    return chain.proceed();
                }
                long seconds = ((Number) raw).longValue();
                if (seconds <= 0L) {
                    return chain.proceed();
                }
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date(seconds * 1000L));
            });
        }
    }

    private void hookStatusLineId(ClassLoader classLoader) {
        Class<?> container = load(classLoader, "org.telegram.ui.Components.ChatAvatarContainer");
        if (container == null) {
            return;
        }
        for (Method method : container.getDeclaredMethods()) {
            if (!"updateSubtitle".equals(method.getName())) {
                continue;
            }
            hook(method, chain -> {
                Object result = chain.proceed();
                if (enabled(EnhancementConfig.Feature.SHOW_ID_IN_STATUS_LINE)) {
                    appendStatusLineId(chain.getThisObject());
                }
                return result;
            });
        }
    }

    private void appendStatusLineId(Object container) {
        Object parent = Reflect.field(container, "parentFragment");
        if (parent == null) {
            parent = Reflect.field(container, "chatActivity");
        }
        long id = Reflect.asLong(Reflect.invokeIfExists(parent, "getDialogId", new Class<?>[0]), 0L);
        if (id == 0L) {
            id = Reflect.asLong(Reflect.field(parent, "dialog_id"), 0L);
        }
        Object subtitle = Reflect.field(container, "subtitleTextView");
        if (id == 0L || subtitle == null) {
            return;
        }
        String current = Reflect.asString(Reflect.invokeIfExists(subtitle, "getText", new Class<?>[0]));
        String suffix = " · ID " + id;
        if (!current.contains(suffix)) {
            Reflect.invokeIfExists(subtitle, "setText", new Class<?>[]{CharSequence.class}, current + suffix);
        }
    }

    private void hookSystemEmoji(ClassLoader classLoader) {
        Class<?> emoji = load(classLoader, "org.telegram.messenger.Emoji");
        if (emoji == null) {
            return;
        }
        for (Method method : emoji.getDeclaredMethods()) {
            if (!"replaceEmoji".equals(method.getName())
                    || method.getParameterCount() == 0
                    || method.getReturnType() != CharSequence.class
                    || !CharSequence.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            hook(method, chain -> enabled(EnhancementConfig.Feature.USE_SYSTEM_EMOJI)
                    ? chain.getArgs().get(0)
                    : chain.proceed());
        }
    }

    private void hookBlurAvailability(ClassLoader classLoader) {
        Class<?> sharedConfig = load(classLoader, "org.telegram.messenger.SharedConfig");
        if (sharedConfig == null) {
            return;
        }
        for (Method method : sharedConfig.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (method.getReturnType() == boolean.class && name.contains("blur")) {
                hook(method, chain -> enabled(EnhancementConfig.Feature.FORCE_CHAT_BLUR)
                        ? true
                        : chain.proceed());
            }
        }
    }

    private void hookHolidayAnimation(ClassLoader classLoader) {
        Class<?> theme = load(classLoader, "org.telegram.ui.ActionBar.Theme");
        if (theme == null) {
            return;
        }
        for (Method method : theme.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (method.getReturnType() == boolean.class
                    && (name.contains("holiday") || name.contains("snow"))) {
                hook(method, chain -> enabled(EnhancementConfig.Feature.FORCE_SNOW_ANIMATION)
                        ? true
                        : chain.proceed());
            }
        }
    }

    private void hookProtocolErrors(ClassLoader classLoader) {
        Class<?> alertsCreator = load(classLoader, "org.telegram.ui.Components.AlertsCreator");
        if (alertsCreator == null) {
            return;
        }
        for (Method method : alertsCreator.getDeclaredMethods()) {
            if ("processError".equals(method.getName())) {
                hook(method, chain -> enabled(EnhancementConfig.Feature.HIDE_PROTOCOL_ERRORS)
                        ? defaultValue(method.getReturnType())
                        : chain.proceed());
            }
        }
    }

    private void hookHomeActions(ClassLoader classLoader) {
        Class<?> dialogs = load(classLoader, "org.telegram.ui.DialogsActivity");
        if (dialogs == null) {
            return;
        }
        for (Method method : dialogs.getDeclaredMethods()) {
            if (!("createView".equals(method.getName()) || "onResume".equals(method.getName()))) {
                continue;
            }
            hook(method, chain -> {
                Object result = chain.proceed();
                if (enabled(EnhancementConfig.Feature.HIDE_HOME_ACTION_BUTTONS)) {
                    hideViewField(chain.getThisObject(), "floatingButton");
                    hideViewField(chain.getThisObject(), "floatingButtonContainer");
                    hideViewField(chain.getThisObject(), "floatingButton2");
                }
                if (enabled(EnhancementConfig.Feature.HIDE_CONTACTS_TAB)) {
                    hideViewField(chain.getThisObject(), "contactsItem");
                    hideViewField(chain.getThisObject(), "contactsButton");
                    hideViewField(chain.getThisObject(), "contactsTab");
                }
                return result;
            });
        }
    }

    private void hookPremiumStickerTab(ClassLoader classLoader) {
        Class<?> emojiView = load(classLoader, "org.telegram.ui.Components.EmojiView");
        if (emojiView == null) {
            return;
        }
        for (Method method : emojiView.getDeclaredMethods()) {
            if (!("updateTabs".equals(method.getName()) || "updateStickerTabs".equals(method.getName()))) {
                continue;
            }
            hook(method, chain -> {
                Object result = chain.proceed();
                if (enabled(EnhancementConfig.Feature.HIDE_PREMIUM_STICKER_TAB)) {
                    hideViewField(chain.getThisObject(), "premiumTab");
                    hideViewField(chain.getThisObject(), "premiumButton");
                }
                return result;
            });
        }
    }

    private void hookServiceStories(ClassLoader classLoader) {
        Class<?> stories = load(classLoader, "org.telegram.ui.Stories.StoriesController");
        if (stories == null) {
            stories = load(classLoader, "org.telegram.messenger.StoriesController");
        }
        if (stories == null) {
            return;
        }
        for (Method method : stories.getDeclaredMethods()) {
            if (!"hasStories".equals(method.getName())
                    || method.getReturnType() != boolean.class
                    || method.getParameterCount() == 0) {
                continue;
            }
            hook(method, chain -> {
                if (enabled(EnhancementConfig.Feature.HIDE_SERVICE_STORIES)
                        && !chain.getArgs().isEmpty()
                        && chain.getArgs().get(0) instanceof Number
                        && ((Number) chain.getArgs().get(0)).longValue() == 777000L) {
                    return false;
                }
                return chain.proceed();
            });
        }
    }

    private void installTransferHooks(ClassLoader classLoader) {
        hookTransferOperation(
                classLoader,
                "org.telegram.messenger.FileLoadOperation",
                EnhancementConfig.Feature.DOWNLOAD_BOOST,
                "maxDownloadRequests",
                "maxRequestsCount",
                "currentMaxDownloadRequests"
        );
        hookTransferOperation(
                classLoader,
                "org.telegram.messenger.FileUploadOperation",
                EnhancementConfig.Feature.UPLOAD_BOOST,
                "maxRequestsCount",
                "maxUploadRequests"
        );
    }

    private void hookTransferOperation(
            ClassLoader classLoader,
            String className,
            EnhancementConfig.Feature feature,
            String... fieldNames
    ) {
        Class<?> operation = load(classLoader, className);
        if (operation == null) {
            return;
        }
        for (Method method : operation.getDeclaredMethods()) {
            if (!("start".equals(method.getName()) || "startDownloadRequest".equals(method.getName()))) {
                continue;
            }
            hook(method, chain -> {
                if (enabled(feature)) {
                    EnhancementConfig config = config();
                    int requests = feature == EnhancementConfig.Feature.DOWNLOAD_BOOST
                            ? config.downloadParallelism
                            : config.uploadParallelism;
                    for (String fieldName : fieldNames) {
                        setPositiveIntField(chain.getThisObject(), fieldName, requests);
                    }
                }
                return chain.proceed();
            });
        }
    }

    private void applyStartupFlags(ClassLoader classLoader) {
        EnhancementConfig config = config();
        if (!config.isEnabled(EnhancementConfig.Feature.TELEGRAM_DEBUG_MODE)
                && !config.isEnabled(EnhancementConfig.Feature.NETWORK_LOG_CONTROL)) {
            return;
        }
        Class<?> buildVars = load(classLoader, "org.telegram.messenger.BuildVars");
        if (buildVars == null) {
            return;
        }
        if (config.isEnabled(EnhancementConfig.Feature.TELEGRAM_DEBUG_MODE)) {
            setStaticBoolean(buildVars, "DEBUG_VERSION", true);
        }
        if (config.isEnabled(EnhancementConfig.Feature.NETWORK_LOG_CONTROL)) {
            setStaticBoolean(buildVars, "LOGS_ENABLED", true);
        }
    }

    private void hookBooleanMethods(
            Class<?> type,
            String name,
            EnhancementConfig.Feature feature,
            boolean enabledResult
    ) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getReturnType() == boolean.class) {
                hook(method, chain -> enabled(feature) ? enabledResult : chain.proceed());
            }
        }
    }

    private void hookBooleanMethodsAny(
            Class<?> type,
            String name,
            boolean enabledResult,
            EnhancementConfig.Feature... features
    ) {
        for (Method method : type.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || method.getReturnType() != boolean.class) {
                continue;
            }
            hook(method, chain -> {
                for (EnhancementConfig.Feature feature : features) {
                    if (enabled(feature)) {
                        return enabledResult;
                    }
                }
                return chain.proceed();
            });
        }
    }

    private EnhancementConfig config() {
        long now = SystemClock.elapsedRealtime();
        EnhancementConfig snapshot = cachedEnhancements;
        if (now - lastConfigRefreshAt < CONFIG_SNAPSHOT_MS) {
            return snapshot;
        }
        synchronized (configLock) {
            now = SystemClock.elapsedRealtime();
            snapshot = cachedEnhancements;
            if (now - lastConfigRefreshAt < CONFIG_SNAPSHOT_MS) {
                return snapshot;
            }
            lastConfigRefreshAt = now;
            XposedConfigProvider provider = configProvider;
            Context context = currentApplication();
            if (provider == null || context == null) {
                return snapshot;
            }
            FilterConfig filterConfig = provider.getConfig(context);
            EnhancementConfig loaded = filterConfig.enhancements == null
                    ? new EnhancementConfig()
                    : filterConfig.enhancements.deepCopy().sanitize();
            cachedEnhancements = loaded;
            return loaded;
        }
    }

    private boolean enabled(EnhancementConfig.Feature feature) {
        return config().isEnabledForGramSieve(feature);
    }

    private Context currentApplication() {
        Context existing = applicationContext;
        if (existing != null) {
            return existing;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            Object value = currentApplication.invoke(null);
            if (!(value instanceof Context)) {
                return null;
            }
            Context context = (Context) value;
            Context appContext = context.getApplicationContext();
            applicationContext = appContext == null ? context : appContext;
            return applicationContext;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isChinese(Context context) {
        return TelegramLocale.isChinese(
                context,
                context == null ? null : context.getClassLoader()
        );
    }

    private void hook(Method method, XposedInterface.Hooker hooker) {
        if (method == null || !hookedMethods.add(method)) {
            return;
        }
        try {
            method.setAccessible(true);
            module.hook(method)
                    .setId(HookIdentity.forCaller("enhancement", method))
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> active ? hooker.intercept(chain) : chain.proceed());
        } catch (Throwable throwable) {
            hookedMethods.remove(method);
            warn("Enhancements: failed to hook " + method.getDeclaringClass().getName() + "." + method.getName());
        }
    }

    private static Class<?> load(ClassLoader classLoader, String className) {
        try {
            return classLoader.loadClass(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static boolean containsAny(String value, String[] needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean peerLooksLikeGroup(Object request) {
        Object peer = Reflect.field(request, "peer");
        String name = peer == null ? "" : peer.getClass().getName();
        return name.contains("InputPeerChat") || name.contains("InputPeerChannel");
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static Object emptyValue(Class<?> returnType) {
        if (List.class.isAssignableFrom(returnType)) {
            return Collections.emptyList();
        }
        if (Set.class.isAssignableFrom(returnType)) {
            return Collections.emptySet();
        }
        if (java.util.Map.class.isAssignableFrom(returnType)) {
            return Collections.emptyMap();
        }
        return defaultValue(returnType);
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static CharSequence asCharSequence(Object value) {
        return value instanceof CharSequence ? (CharSequence) value : null;
    }

    private static void addToIntField(Object target, String fieldName, int delta) {
        Field field = findField(target.getClass(), fieldName);
        if (field == null || field.getType() != int.class) {
            return;
        }
        try {
            field.setInt(target, field.getInt(target) + delta);
        } catch (IllegalAccessException ignored) {
            // Best-effort compatibility across Telegram versions.
        }
    }

    private static void setPositiveIntField(Object target, String fieldName, int value) {
        Field field = findField(target.getClass(), fieldName);
        if (field == null || field.getType() != int.class) {
            return;
        }
        try {
            field.setInt(target, Math.max(field.getInt(target), value));
        } catch (IllegalAccessException ignored) {
            // Best-effort compatibility across Telegram versions.
        }
    }

    private static void hideViewField(Object owner, String fieldName) {
        Object value = Reflect.field(owner, fieldName);
        if (value instanceof View) {
            ((View) value).setVisibility(View.GONE);
        }
    }

    private static void setStaticBoolean(Class<?> type, String fieldName, boolean value) {
        Field field = findField(type, fieldName);
        if (field == null || field.getType() != boolean.class || !Modifier.isStatic(field.getModifiers())) {
            return;
        }
        try {
            field.setBoolean(null, value);
        } catch (IllegalAccessException ignored) {
            // Telegram may inline a final flag; this feature simply becomes unavailable.
        }
    }

    private void info(String message) {
        ModuleLogger.hook(TAG, message);
    }

    private void debug(String message) {
        ModuleLogger.hook(TAG, message);
    }

    private void warn(String message) {
        ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG, message);
    }
}
