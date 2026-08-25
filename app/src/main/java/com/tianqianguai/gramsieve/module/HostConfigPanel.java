package com.tianqianguai.gramsieve.module;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.RuleDraftMatrix;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@SuppressLint("UseSwitchCompatOrMaterialCode")
final class HostConfigPanel {
    private enum DialogHistoryChoice {
        FOLLOW_GLOBAL,
        RECORD,
        SKIP
    }

    interface ConfigSaver {
        FilterConfig save(FilterConfig updated);
    }

    private static final FilterConfig.RuleTarget[] GLOBAL_TARGETS = new FilterConfig.RuleTarget[]{
            FilterConfig.RuleTarget.ANY,
            FilterConfig.RuleTarget.TEXT,
            FilterConfig.RuleTarget.CAPTION,
            FilterConfig.RuleTarget.BUTTONS,
            FilterConfig.RuleTarget.SENDER,
            FilterConfig.RuleTarget.CHAT
    };
    private static final FilterConfig.RuleTarget[] CHAT_TARGETS = new FilterConfig.RuleTarget[]{
            FilterConfig.RuleTarget.ANY,
            FilterConfig.RuleTarget.TEXT,
            FilterConfig.RuleTarget.CAPTION,
            FilterConfig.RuleTarget.BUTTONS,
            FilterConfig.RuleTarget.SENDER
    };

    private final Context context;
    private final ViewGroup root;
    private final FilterConfig baseConfig;
    private final boolean chatMode;
    private final long dialogId;
    private final String chatTitle;
    private final int accountId;
    private final AntiRecallConfigStore antiRecallConfigStore;
    private final BackgroundMessageLoader backgroundMessageLoader;
    private final EditHistoryPolicyStore editHistoryPolicyStore;
    private final ConfigSaver saver;
    private final Runnable afterSave;
    private final boolean chinese;
    private final int backgroundColor;
    private final int cardColor;
    private final int primaryTextColor;
    private final int secondaryTextColor;
    private final int strokeColor;
    private final int accentColor;
    private final int toolbarColor;
    private final int toolbarTextColor;
    private final Map<FilterConfig.RuleTarget, RuleInputs> ruleInputs =
            new EnumMap<>(FilterConfig.RuleTarget.class);

    private FrameLayout overlay;
    private OnBackInvokedDispatcher backDispatcher;
    private OnBackInvokedCallback backCallback;
    private Switch enabledSwitch;
    private Switch debugLoggingSwitch;
    private Switch excludeChatSwitch;
    private Switch chatAntiRecallSwitch;
    private Switch editHistoryEnabledSwitch;
    private RadioGroup languageGroup;
    private RadioGroup actionGroup;
    private RadioGroup editHistoryModeGroup;
    private RadioGroup dialogHistoryRuleGroup;

    private HostConfigPanel(
            Context context,
            ViewGroup root,
            FilterConfig baseConfig,
            boolean chatMode,
            long dialogId,
            String chatTitle,
            int accountId,
            AntiRecallConfigStore antiRecallConfigStore,
            BackgroundMessageLoader backgroundMessageLoader,
            EditHistoryPolicyStore editHistoryPolicyStore,
            ConfigSaver saver,
            Runnable afterSave
    ) {
        this.context = context;
        this.root = root;
        this.baseConfig = baseConfig == null ? FilterConfig.createDefault() : baseConfig.deepCopy().sanitize();
        this.chatMode = chatMode;
        this.dialogId = dialogId;
        this.chatTitle = chatTitle == null ? "" : chatTitle;
        this.accountId = Math.max(0, accountId);
        this.antiRecallConfigStore = antiRecallConfigStore;
        this.backgroundMessageLoader = backgroundMessageLoader;
        this.editHistoryPolicyStore = editHistoryPolicyStore;
        this.saver = saver;
        this.afterSave = afterSave;
        this.chinese = isChineseLocale(context);
        int androidBackground = resolveThemeColor(android.R.attr.colorBackground, Color.rgb(18, 18, 18));
        int androidPrimaryText = resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE);
        int androidSecondaryText = resolveThemeColor(
                android.R.attr.textColorSecondary,
                adjustAlpha(androidPrimaryText, 0.68f)
        );
        int androidAccent = resolveThemeColor(android.R.attr.colorAccent, Color.rgb(42, 171, 238));
        this.backgroundColor = telegramThemeColor("key_windowBackgroundWhite", androidBackground);
        this.cardColor = backgroundColor;
        this.primaryTextColor = telegramThemeColor("key_windowBackgroundWhiteBlackText", androidPrimaryText);
        this.secondaryTextColor = telegramThemeColor("key_windowBackgroundWhiteGrayText2", androidSecondaryText);
        this.strokeColor = telegramThemeColor("key_divider", adjustAlpha(primaryTextColor, 0.14f));
        this.toolbarColor = telegramThemeColor("key_actionBarDefault", backgroundColor);
        this.toolbarTextColor = telegramThemeColor("key_actionBarDefaultTitle", primaryTextColor);
        this.accentColor = telegramThemeColor("key_actionBarDefaultIcon", androidAccent);
    }

    static boolean show(
            Context context,
            ViewGroup root,
            FilterConfig config,
            boolean chatMode,
            long dialogId,
            String chatTitle,
            int accountId,
            AntiRecallConfigStore antiRecallConfigStore,
            BackgroundMessageLoader backgroundMessageLoader,
            EditHistoryPolicyStore editHistoryPolicyStore,
            ConfigSaver saver,
            Runnable afterSave
    ) {
        if (context == null || root == null || saver == null) {
            return false;
        }
        closeExisting(root);
        HostConfigPanel panel = new HostConfigPanel(
                context,
                root,
                config,
                chatMode,
                dialogId,
                chatTitle,
                accountId,
                antiRecallConfigStore,
                backgroundMessageLoader,
                editHistoryPolicyStore,
                saver,
                afterSave
        );
        panel.attach();
        return true;
    }

    static boolean closeExisting(ViewGroup root) {
        if (root == null) {
            return false;
        }
        View existing = root.findViewById(R.id.gramsieve_host_config_panel_id);
        if (existing == null) {
            return false;
        }
        Object owner = existing.getTag();
        if (owner instanceof HostConfigPanel) {
            ((HostConfigPanel) owner).close();
            return true;
        }
        ViewGroup parent = (ViewGroup) existing.getParent();
        if (parent != null) {
            parent.removeView(existing);
            return true;
        }
        return false;
    }

    static FilterConfig applyGlobalDraft(
            FilterConfig original,
            boolean enabled,
            boolean debugLogging,
            String appLanguageTag,
            FilterConfig.Action action,
            RuleDraftMatrix matchMatrix,
            RuleDraftMatrix exclusionMatrix
    ) {
        FilterConfig updated = original == null ? FilterConfig.createDefault() : original.deepCopy();
        updated.enabled = enabled;
        updated.debugLogging = debugLogging;
        updated.appLanguageTag = appLanguageTag;
        updated.action = action == null ? FilterConfig.Action.HIDE : action;
        updated.globalRules = exportRules(matchMatrix, false);
        updated.globalExclusions = exportRules(exclusionMatrix, false);
        updated.updatedAtEpochMs = System.currentTimeMillis();
        return updated.sanitize();
    }

    static FilterConfig applyChatDraft(
            FilterConfig original,
            long dialogId,
            boolean enabled,
            boolean excludeFromGlobal,
            RuleDraftMatrix matchMatrix,
            RuleDraftMatrix exclusionMatrix
    ) {
        FilterConfig updated = original == null ? FilterConfig.createDefault() : original.deepCopy();
        FilterConfig.ChatRuleSet chatRuleSet = updated.getOrCreateChatRuleSet(dialogId);
        chatRuleSet.enabled = enabled;
        chatRuleSet.excludeFromGlobal = excludeFromGlobal;
        chatRuleSet.rules = exportRules(matchMatrix, true);
        chatRuleSet.exclusions = exportRules(exclusionMatrix, true);
        chatRuleSet.sanitize();
        if (chatRuleSet.isSemanticallyEmpty()) {
            updated.chatRules.remove(FilterConfig.chatKey(dialogId));
        }
        updated.updatedAtEpochMs = System.currentTimeMillis();
        return updated.sanitize();
    }

    private static List<FilterConfig.RuleSpec> exportRules(RuleDraftMatrix source, boolean chatMode) {
        RuleDraftMatrix exported = new RuleDraftMatrix();
        RuleDraftMatrix safeSource = source == null ? new RuleDraftMatrix() : source;
        for (FilterConfig.RuleTarget target : editorTargets(chatMode)) {
            exported.set(target, FilterConfig.RuleMode.KEYWORD, safeSource.get(target, FilterConfig.RuleMode.KEYWORD));
            exported.set(target, FilterConfig.RuleMode.REGEX, safeSource.get(target, FilterConfig.RuleMode.REGEX));
        }
        return exported.toRules();
    }

    private static FilterConfig.RuleTarget[] editorTargets(boolean chatMode) {
        return chatMode ? CHAT_TARGETS : GLOBAL_TARGETS;
    }

    private void attach() {
        overlay = new FrameLayout(context);
        overlay.setId(R.id.gramsieve_host_config_panel_id);
        overlay.setTag(this);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setBackgroundColor(backgroundColor);
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        overlay.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    close();
                }
                return true;
            }
            return false;
        });
        overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                unregisterSystemBackHandler();
            }
        });

        LinearLayout screen = new LinearLayout(context);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(backgroundColor);
        overlay.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        screen.addView(createToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
        ));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(backgroundColor);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(backgroundColor);
        int padding = dp(16);
        container.setPadding(padding, dp(12), padding, dp(24));
        scrollView.addView(container, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        screen.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        buildGeneralCard(container);
        if (chatMode) {
            buildChatAntiRecallCard(container);
        }
        buildEditHistoryCard(container);
        buildRulesCard(container);

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.bringToFront();
        overlay.requestApplyInsets();
        overlay.requestFocus();
        overlay.requestFocusFromTouch();
        registerSystemBackHandler();
    }

    private View createToolbar() {
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), 0, dp(8), 0);
        toolbar.setBackgroundColor(toolbarColor);

        Button backButton = toolbarButton(t("返回", "Back"));
        backButton.setOnClickListener(v -> close());
        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText(chatMode ? t("GramSieve 聊天设置", "GramSieve Chat Settings") : t("GramSieve 设置", "GramSieve Settings"));
        title.setTextColor(toolbarTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button saveButton = toolbarButton(t("保存", "Save"));
        saveButton.setOnClickListener(v -> save());
        toolbar.addView(saveButton, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        return toolbar;
    }

    private void buildGeneralCard(LinearLayout container) {
        LinearLayout card = addCard(container);
        addTitle(card, chatMode ? t("当前聊天", "Current Chat") : t("全局配置", "Global Settings"));

        FilterConfig.ChatRuleSet chatRuleSet = chatMode
                ? baseConfig.getOrCreateChatRuleSet(dialogId).deepCopy().sanitize()
                : null;

        enabledSwitch = addSwitch(card, t("启用过滤", "Enable filtering"));
        enabledSwitch.setChecked(chatMode ? chatRuleSet.enabled : baseConfig.enabled);

        if (chatMode) {
            excludeChatSwitch = addSwitch(card, t("这个聊天不使用全局规则", "Do not apply global rules to this chat"));
            excludeChatSwitch.setChecked(chatRuleSet.excludeFromGlobal);
            String scope = chatTitle.isBlank()
                    ? t("当前聊天 ID：", "Current chat ID: ") + dialogId
                    : t("当前聊天：", "Current chat: ") + chatTitle;
            addInfo(card, scope);
            return;
        }

        debugLoggingSwitch = addSwitch(card, t("详细调试日志", "Verbose debug logging"));
        debugLoggingSwitch.setChecked(baseConfig.debugLogging);

        addSectionLabel(card, t("界面语言", "Interface language"));
        languageGroup = new RadioGroup(context);
        languageGroup.setOrientation(RadioGroup.VERTICAL);
        addRadio(languageGroup, t("跟随系统", "Follow system"), FilterConfig.APP_LANGUAGE_SYSTEM);
        addRadio(languageGroup, "English", FilterConfig.APP_LANGUAGE_ENGLISH);
        addRadio(languageGroup, "简体中文", FilterConfig.APP_LANGUAGE_SIMPLIFIED_CHINESE);
        checkTaggedRadio(languageGroup, FilterConfig.normalizeAppLanguageTag(baseConfig.appLanguageTag));
        addView(card, languageGroup, 8);

        addSectionLabel(card, t("匹配后的动作", "Match action"));
        actionGroup = new RadioGroup(context);
        actionGroup.setOrientation(RadioGroup.VERTICAL);
        addRadio(actionGroup, t("仅本地隐藏", "Hide locally"), FilterConfig.Action.HIDE);
        addRadio(actionGroup, t("仅本地折叠", "Collapse locally"), FilterConfig.Action.COLLAPSE);
        addRadio(actionGroup, t("调试标记", "Debug mark"), FilterConfig.Action.DEBUG_MARK);
        checkTaggedRadio(actionGroup, baseConfig.action == null ? FilterConfig.Action.HIDE : baseConfig.action);
        addView(card, actionGroup, 0);
    }

    private void buildChatAntiRecallCard(LinearLayout container) {
        if (antiRecallConfigStore == null || dialogId == 0L) {
            return;
        }
        LinearLayout card = addCard(container);
        addTitle(card, t("当前聊天主动加载", "Current Chat Proactive Loading"));
        chatAntiRecallSwitch = addSwitch(card, t("为这个聊天启用主动加载/防撤回", "Enable proactive loading for this chat"));
        chatAntiRecallSwitch.setChecked(isChatAntiRecallEnabled());
        addInfo(card, t("保存后会立即同步到宿主里的后台加载器。", "Saving updates the host background loader immediately."));
    }

    private void buildEditHistoryCard(LinearLayout container) {
        if (editHistoryPolicyStore == null || (chatMode && dialogId == 0L)) {
            return;
        }
        LinearLayout card = addCard(container);
        addTitle(card, t("编辑历史", "Edit History"));
        if (chatMode) {
            addInfo(card, t(
                    "为当前用户或群组设置记录规则；“跟随全局”不会创建单独规则。",
                    "Choose the rule for this user or group. Follow global creates no override."
            ));
            dialogHistoryRuleGroup = new RadioGroup(context);
            dialogHistoryRuleGroup.setOrientation(RadioGroup.VERTICAL);
            addRadio(dialogHistoryRuleGroup, t("跟随全局模式", "Follow global mode"),
                    DialogHistoryChoice.FOLLOW_GLOBAL);
            addRadio(dialogHistoryRuleGroup, t("始终记录", "Always record"), DialogHistoryChoice.RECORD);
            addRadio(dialogHistoryRuleGroup, t("从不记录", "Never record"), DialogHistoryChoice.SKIP);
            Boolean explicit = editHistoryPolicyStore.getDialogRule(accountId, dialogId);
            checkTaggedRadio(dialogHistoryRuleGroup, explicit == null
                    ? DialogHistoryChoice.FOLLOW_GLOBAL
                    : explicit ? DialogHistoryChoice.RECORD : DialogHistoryChoice.SKIP);
            addView(card, dialogHistoryRuleGroup, 0);
            return;
        }

        editHistoryEnabledSwitch = addSwitch(card, t("启用编辑历史", "Enable edit history"));
        editHistoryEnabledSwitch.setChecked(editHistoryPolicyStore.isEnabled(accountId));
        addSectionLabel(card, t("默认记录范围", "Default recording scope"));
        editHistoryModeGroup = new RadioGroup(context);
        editHistoryModeGroup.setOrientation(RadioGroup.VERTICAL);
        addRadio(editHistoryModeGroup,
                t("黑名单：默认记录，仅排除指定用户/群组", "Blacklist: record by default, except selected users/groups"),
                EditHistoryPolicyStore.Mode.BLACKLIST);
        addRadio(editHistoryModeGroup,
                t("白名单：默认不记录，仅记录指定用户/群组", "Whitelist: only record selected users/groups"),
                EditHistoryPolicyStore.Mode.WHITELIST);
        checkTaggedRadio(editHistoryModeGroup, editHistoryPolicyStore.getMode(accountId));
        addView(card, editHistoryModeGroup, 0);
        addInfo(card, t("账号槽位：", "Account slot: ") + accountId);
    }

    private void buildRulesCard(LinearLayout container) {
        LinearLayout card = addCard(container);
        addTitle(card, t("规则内容", "Rules"));
        addInfo(card, chatMode
                ? t("这里只影响当前聊天。每行一条；输入框已经固定检查目标，不需要 text:、sender: 这类前缀。", "This editor only affects the current chat. One rule per line; each box already has a fixed target, so prefixes are not needed.")
                : t("每行一条；输入框已经固定检查目标，不需要 text:、sender:、chat: 这类前缀。", "One rule per line. Each box already has a fixed target, so text:, sender:, and chat: prefixes are not needed."));

        FilterConfig.ChatRuleSet chatRuleSet = chatMode
                ? baseConfig.getOrCreateChatRuleSet(dialogId).deepCopy().sanitize()
                : null;
        RuleDraftMatrix matchMatrix = RuleDraftMatrix.fromRules(chatMode ? chatRuleSet.rules : baseConfig.globalRules);
        RuleDraftMatrix exclusionMatrix = RuleDraftMatrix.fromRules(chatMode ? chatRuleSet.exclusions : baseConfig.globalExclusions);

        for (FilterConfig.RuleTarget target : editorTargets(chatMode)) {
            addDivider(card);
            addSectionLabel(card, targetLabel(target));
            addInfo(card, targetScope(target));

            RuleInputs inputs = new RuleInputs();
            inputs.matchKeywords = addInput(card, t("过滤关键词", "Filter keywords"), matchMatrix.get(target, FilterConfig.RuleMode.KEYWORD));
            inputs.matchRegex = addInput(card, t("过滤正则", "Filter regex"), matchMatrix.get(target, FilterConfig.RuleMode.REGEX));
            inputs.keepKeywords = addInput(card, t("保留关键词", "Keep keywords"), exclusionMatrix.get(target, FilterConfig.RuleMode.KEYWORD));
            inputs.keepRegex = addInput(card, t("保留正则", "Keep regex"), exclusionMatrix.get(target, FilterConfig.RuleMode.REGEX));
            ruleInputs.put(target, inputs);
        }
    }

    private void save() {
        try {
            RuleDraftMatrix matchMatrix = collectMatrix(0);
            RuleDraftMatrix exclusionMatrix = collectMatrix(1);
            FilterConfig updated;
            if (chatMode) {
                updated = applyChatDraft(
                        baseConfig,
                        dialogId,
                        enabledSwitch != null && enabledSwitch.isChecked(),
                        excludeChatSwitch != null && excludeChatSwitch.isChecked(),
                        matchMatrix,
                        exclusionMatrix
                );
                persistChatAntiRecall();
                persistChatEditHistoryRule();
            } else {
                updated = applyGlobalDraft(
                        baseConfig,
                        enabledSwitch != null && enabledSwitch.isChecked(),
                        debugLoggingSwitch != null && debugLoggingSwitch.isChecked(),
                        selectedLanguageTag(),
                        selectedAction(),
                        matchMatrix,
                        exclusionMatrix
                );
                persistGlobalEditHistoryPolicy();
            }
            saver.save(updated);
            if (afterSave != null) {
                afterSave.run();
            }
            Toast.makeText(context, t("已保存并立即生效", "Saved and applied"), Toast.LENGTH_SHORT).show();
            close();
        } catch (Throwable throwable) {
            Toast.makeText(
                    context,
                    t("保存失败：", "Save failed: ") + throwable.getClass().getSimpleName(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private RuleDraftMatrix collectMatrix(int kind) {
        RuleDraftMatrix matrix = new RuleDraftMatrix();
        for (Map.Entry<FilterConfig.RuleTarget, RuleInputs> entry : ruleInputs.entrySet()) {
            RuleInputs inputs = entry.getValue();
            if (kind == 0) {
                matrix.set(entry.getKey(), FilterConfig.RuleMode.KEYWORD, valueOf(inputs.matchKeywords));
                matrix.set(entry.getKey(), FilterConfig.RuleMode.REGEX, valueOf(inputs.matchRegex));
            } else {
                matrix.set(entry.getKey(), FilterConfig.RuleMode.KEYWORD, valueOf(inputs.keepKeywords));
                matrix.set(entry.getKey(), FilterConfig.RuleMode.REGEX, valueOf(inputs.keepRegex));
            }
        }
        return matrix;
    }

    private boolean isChatAntiRecallEnabled() {
        if (backgroundMessageLoader != null) {
            return backgroundMessageLoader.isChatEnabled(dialogId);
        }
        return antiRecallConfigStore != null && antiRecallConfigStore.isChatEnabled(dialogId);
    }

    private void persistChatAntiRecall() {
        if (chatAntiRecallSwitch == null || antiRecallConfigStore == null || dialogId == 0L) {
            return;
        }
        boolean enabled = chatAntiRecallSwitch.isChecked();
        if (backgroundMessageLoader != null) {
            if (enabled) {
                backgroundMessageLoader.enableChat(dialogId);
            } else {
                backgroundMessageLoader.disableChat(dialogId);
            }
            return;
        }
        antiRecallConfigStore.setChatEnabled(dialogId, enabled);
    }

    private void persistGlobalEditHistoryPolicy() {
        if (editHistoryPolicyStore == null || editHistoryEnabledSwitch == null) {
            return;
        }
        editHistoryPolicyStore.setEnabled(accountId, editHistoryEnabledSwitch.isChecked());
        Object selected = selectedRadioTag(editHistoryModeGroup);
        if (selected instanceof EditHistoryPolicyStore.Mode) {
            editHistoryPolicyStore.setMode(accountId, (EditHistoryPolicyStore.Mode) selected);
        }
    }

    private void persistChatEditHistoryRule() {
        if (editHistoryPolicyStore == null || dialogHistoryRuleGroup == null || dialogId == 0L) {
            return;
        }
        Object selected = selectedRadioTag(dialogHistoryRuleGroup);
        if (selected == DialogHistoryChoice.RECORD) {
            editHistoryPolicyStore.setDialogRecorded(accountId, dialogId, true);
        } else if (selected == DialogHistoryChoice.SKIP) {
            editHistoryPolicyStore.setDialogRecorded(accountId, dialogId, false);
        } else {
            editHistoryPolicyStore.clearDialogRule(accountId, dialogId);
        }
    }

    private void close() {
        if (overlay == null) {
            return;
        }
        FrameLayout panel = overlay;
        overlay = null;
        unregisterSystemBackHandler();
        ViewGroup parent = (ViewGroup) panel.getParent();
        if (parent != null) {
            parent.removeView(panel);
        }
    }

    private void registerSystemBackHandler() {
        Activity activity = activityFromContext(context);
        if (activity == null || backCallback != null) {
            return;
        }
        try {
            backDispatcher = activity.getOnBackInvokedDispatcher();
            backCallback = this::close;
            backDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    backCallback
            );
        } catch (RuntimeException ignored) {
            backDispatcher = null;
            backCallback = null;
        }
    }

    private void unregisterSystemBackHandler() {
        OnBackInvokedDispatcher dispatcher = backDispatcher;
        OnBackInvokedCallback callback = backCallback;
        backDispatcher = null;
        backCallback = null;
        if (dispatcher == null || callback == null) {
            return;
        }
        try {
            dispatcher.unregisterOnBackInvokedCallback(callback);
        } catch (RuntimeException ignored) {
            // The host may already be tearing down its window.
        }
    }

    private static Activity activityFromContext(Context context) {
        Context current = context;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                break;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    private LinearLayout addCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(cardColor, dp(8), strokeColor));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(12);
        parent.addView(card, params);
        return card;
    }

    private TextView addTitle(LinearLayout parent, String text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(primaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(parent, title, 8);
        return title;
    }

    private TextView addSectionLabel(LinearLayout parent, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(primaryTextColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        parent.addView(label, params);
        return label;
    }

    private TextView addInfo(LinearLayout parent, String text) {
        TextView info = new TextView(context);
        info.setText(text);
        info.setTextColor(secondaryTextColor);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        addView(parent, info, 6);
        return info;
    }

    private Switch addSwitch(LinearLayout parent, String text) {
        Switch toggle = new Switch(context);
        toggle.setText(text);
        toggle.setTextColor(primaryTextColor);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        addView(parent, toggle, 6);
        return toggle;
    }

    private EditText addInput(LinearLayout parent, String label, String initialValue) {
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextColor(secondaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        addView(parent, title, 2);

        EditText input = new EditText(context);
        input.setText(initialValue == null ? "" : initialValue);
        input.setTextColor(primaryTextColor);
        input.setHintTextColor(secondaryTextColor);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHorizontallyScrolling(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addView(parent, input, 8);
        return input;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(context);
        divider.setBackgroundColor(strokeColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
        );
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        parent.addView(divider, params);
    }

    private void addView(LinearLayout parent, View view, int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(bottomMarginDp);
        parent.addView(view, params);
    }

    private Button toolbarButton(String text) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(accentColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private void addRadio(RadioGroup group, String text, Object value) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTextColor(primaryTextColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTag(value);
        group.addView(button, new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void checkTaggedRadio(RadioGroup group, Object value) {
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Object tag = child.getTag();
            if (value == null ? tag == null : value.equals(tag)) {
                group.check(child.getId());
                return;
            }
        }
        if (group.getChildCount() > 0) {
            group.check(group.getChildAt(0).getId());
        }
    }

    private String selectedLanguageTag() {
        Object tag = selectedRadioTag(languageGroup);
        return tag instanceof String ? (String) tag : FilterConfig.APP_LANGUAGE_SYSTEM;
    }

    private FilterConfig.Action selectedAction() {
        Object tag = selectedRadioTag(actionGroup);
        return tag instanceof FilterConfig.Action ? (FilterConfig.Action) tag : FilterConfig.Action.HIDE;
    }

    private Object selectedRadioTag(RadioGroup group) {
        if (group == null) {
            return null;
        }
        int checkedId = group.getCheckedRadioButtonId();
        View checked = group.findViewById(checkedId);
        return checked == null ? null : checked.getTag();
    }

    private String targetLabel(FilterConfig.RuleTarget target) {
        switch (target == null ? FilterConfig.RuleTarget.ANY : target) {
            case TEXT:
                return t("消息文字", "Message text");
            case CAPTION:
                return t("媒体说明", "Caption");
            case BUTTONS:
                return t("按钮", "Buttons");
            case SENDER:
                return t("发送者", "Sender");
            case CHAT:
                return t("聊天", "Chat");
            case ANY:
            default:
                return t("全字段", "Any field");
        }
    }

    private String targetScope(FilterConfig.RuleTarget target) {
        switch (target == null ? FilterConfig.RuleTarget.ANY : target) {
            case TEXT:
                return t("只检查消息文字。", "Only checks message text.");
            case CAPTION:
                return t("只检查媒体说明。", "Only checks media captions.");
            case BUTTONS:
                return t("只检查按钮文字或按钮链接。", "Only checks button labels or URLs.");
            case SENDER:
                return t("只检查发送者名称或发送者 ID。", "Only checks sender names or IDs.");
            case CHAT:
                return t("只检查聊天名称或聊天 ID。", "Only checks chat names or IDs.");
            case ANY:
            default:
                return t("检查消息文字、媒体说明、按钮、发送者、聊天名或对应 ID。", "Checks message text, captions, buttons, senders, chat names, or matching IDs.");
        }
    }

    private String t(String zh, String en) {
        return chinese ? zh : en;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()
        ));
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            return fallback;
        }
        if (value.resourceId != 0) {
            try {
                return context.getColor(value.resourceId);
            } catch (Resources.NotFoundException ignored) {
                try {
                    ColorStateList colors = context.getColorStateList(value.resourceId);
                    return colors == null ? fallback : colors.getDefaultColor();
                } catch (Resources.NotFoundException ignoredAgain) {
                    return fallback;
                }
            }
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        return fallback;
    }

    private int telegramThemeColor(String keyFieldName, int fallback) {
        try {
            ClassLoader classLoader = context.getClassLoader();
            Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme", false, classLoader);
            Field keyField = themeClass.getDeclaredField(keyFieldName);
            keyField.setAccessible(true);
            int key = keyField.getInt(null);
            Method getColor = themeClass.getDeclaredMethod("getColor", int.class);
            getColor.setAccessible(true);
            Object color = getColor.invoke(null, key);
            return color instanceof Integer ? (Integer) color : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(1, strokeColor);
        return drawable;
    }

    private static int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static boolean isChineseLocale(Context context) {
        try {
            Locale locale = context.getResources().getConfiguration().locale;
            return locale != null && "zh".equalsIgnoreCase(locale.getLanguage());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String valueOf(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString();
    }

    private static final class RuleInputs {
        EditText matchKeywords;
        EditText matchRegex;
        EditText keepKeywords;
        EditText keepRegex;
    }
}
