package com.tianqianguai.gramsieve.module;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import com.tianqianguai.gramsieve.config.LogFileSupport;
import com.tianqianguai.gramsieve.config.ModuleLogger;
import com.tianqianguai.gramsieve.config.RuntimeModuleProbe;
import com.tianqianguai.gramsieve.core.EnhancementConfig;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.ModuleConflictDetector;
import com.tianqianguai.gramsieve.core.RuleDraftMatrix;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

@SuppressLint({"UseSwitchCompatOrMaterialCode", "SetTextI18n"})
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
    private final XposedModule module;
    private final boolean chinese;
    private final int backgroundColor;
    private final int cardColor;
    private final int primaryTextColor;
    private final int secondaryTextColor;
    private final int strokeColor;
    private final int accentColor;
    private final int heroStartColor;
    private final int heroEndColor;
    private final int toolbarColor;
    private final int toolbarTextColor;
    private final Map<FilterConfig.RuleTarget, RuleInputs> ruleInputs =
            new EnumMap<>(FilterConfig.RuleTarget.class);
    private final Map<EnhancementConfig.Feature, Switch> enhancementSwitches =
            new EnumMap<>(EnhancementConfig.Feature.class);
    private final Map<ModuleConflictDetector.KnownModule, Switch> moduleFallbackSwitches =
            new EnumMap<>(ModuleConflictDetector.KnownModule.class);

    private FrameLayout overlay;
    private OnBackInvokedDispatcher backDispatcher;
    private OnBackInvokedCallback backCallback;
    private int moduleProbeGeneration;
    private int logConsoleGeneration;
    private Switch enabledSwitch;
    private Switch debugLoggingSwitch;
    private Switch excludeChatSwitch;
    private Switch chatAntiRecallSwitch;
    private Switch editHistoryEnabledSwitch;
    private RadioGroup languageGroup;
    private RadioGroup actionGroup;
    private RadioGroup editHistoryModeGroup;
    private RadioGroup dialogHistoryRuleGroup;
    private EditText downloadParallelismInput;
    private EditText uploadParallelismInput;
    private EditText outgoingPrefixInput;
    private EditText outgoingSuffixInput;
    private TextView logConsoleStatus;
    private TextView logConsoleOutput;

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
            Runnable afterSave,
            XposedModule module
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
        this.module = module;
        this.chinese = isChineseLocale(context);
        int androidBackground = resolveThemeColor(android.R.attr.colorBackground, Color.rgb(18, 18, 18));
        int androidPrimaryText = resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE);
        int androidSecondaryText = resolveThemeColor(
                android.R.attr.textColorSecondary,
                adjustAlpha(androidPrimaryText, 0.68f)
        );
        int androidAccent = resolveThemeColor(android.R.attr.colorAccent, Color.rgb(42, 171, 238));
        this.primaryTextColor = telegramThemeColor("key_windowBackgroundWhiteBlackText", androidPrimaryText);
        this.backgroundColor = telegramThemeColor("key_windowBackgroundWhite", androidBackground);
        this.cardColor = blend(backgroundColor, primaryTextColor, 0.075f);
        int themedSecondary = telegramThemeColor("key_windowBackgroundWhiteGrayText2", androidSecondaryText);
        this.secondaryTextColor = blend(themedSecondary, primaryTextColor, 0.24f);
        this.strokeColor = blend(backgroundColor, primaryTextColor, 0.18f);
        this.toolbarColor = telegramThemeColor("key_actionBarDefault", backgroundColor);
        this.toolbarTextColor = telegramThemeColor("key_actionBarDefaultTitle", primaryTextColor);
        this.accentColor = telegramThemeColor("key_windowBackgroundWhiteBlueText", androidAccent);
        this.heroStartColor = ensureWhiteTextContrast(accentColor);
        this.heroEndColor = ensureWhiteTextContrast(
                blend(heroStartColor, Color.rgb(104, 76, 226), 0.58f)
        );
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
            Runnable afterSave,
            XposedModule module
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
                afterSave,
                module
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

        buildHeroCard(container);
        buildGeneralCard(container);
        if (chatMode) {
            buildChatAntiRecallCard(container);
        } else {
            buildConflictCard(container);
            buildUntestedFeaturesEntry(container);
        }
        buildEditHistoryCard(container);
        buildLogCard(container);
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

    private void buildHeroCard(LinearLayout container) {
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{heroStartColor, heroEndColor}
        );
        gradient.setCornerRadius(dp(22));
        hero.setBackground(gradient);
        hero.setElevation(dp(5));

        TextView eyebrow = new TextView(context);
        eyebrow.setText(chatMode ? t("GRAMSIEVE · 当前聊天", "GRAMSIEVE · CURRENT CHAT")
                : t("GRAMSIEVE · TELEGRAM 工具箱", "GRAMSIEVE · TELEGRAM TOOLBOX"));
        eyebrow.setTextColor(adjustAlpha(Color.WHITE, 0.78f));
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(hero, eyebrow, 8);

        TextView title = new TextView(context);
        title.setText(chatMode ? t("净化这个聊天，留下真正重要的内容", "Keep what matters in this chat")
                : t("过滤是核心，增强能力由你选择", "Filtering first. Enhancements on your terms."));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(hero, title, 8);

        TextView summary = new TextView(context);
        summary.setText(chatMode
                ? t("规则、防撤回与标记仍按聊天独立管理。", "Rules, anti-recall, and marks stay scoped to this chat.")
                : t("核心设置保持直接可见；未经逐项验证的增强统一收纳并默认关闭。", "Core settings stay visible. Enhancements without per-feature verification are grouped separately and remain opt-in."));
        summary.setTextColor(adjustAlpha(Color.WHITE, 0.88f));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        addView(hero, summary, 0);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(14);
        container.addView(hero, params);
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
        if (baseConfig.enhancements != null
                && baseConfig.enhancements.yieldsToModule(ModuleConflictDetector.ConflictKind.ANTI_RECALL)) {
            addInfo(card, t(
                    "当前由外部模块接管防撤回；此处设置会保留，关闭模块回退后恢复使用。",
                    "Anti-recall is currently delegated to another module. This setting is preserved and resumes when module fallback is disabled."
            ));
        }
        addInfo(card, t("保存后会立即同步到宿主里的后台加载器。", "Saving updates the host background loader immediately."));
    }

    private void buildEditHistoryCard(LinearLayout container) {
        if (editHistoryPolicyStore == null || (chatMode && dialogId == 0L)) {
            return;
        }
        LinearLayout card = addCard(container);
        addTitle(card, t("编辑历史", "Edit History"));
        if (baseConfig.enhancements != null
                && baseConfig.enhancements.yieldsToModule(ModuleConflictDetector.ConflictKind.EDIT_HISTORY)) {
            addInfo(card, t(
                    "当前由外部模块接管编辑历史；GramSieve 仍保留这些设置，关闭模块回退后恢复使用。",
                    "Edit history is currently delegated to another module. GramSieve preserves these settings and resumes when module fallback is disabled."
            ));
        }
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

    private void buildEnhancementCards(LinearLayout container) {
        addCategoryStrip(container);
        for (EnhancementConfig.Category category : EnhancementConfig.Category.values()) {
            LinearLayout card = addCard(container);
            addCategoryHeader(card, category);
            for (EnhancementConfig.Feature feature : EnhancementConfig.Feature.values()) {
                if (feature.category != category || !feature.isAvailableInCurrentBuild()) {
                    continue;
                }
                Switch toggle = addFeatureSwitch(card, featureTitle(feature));
                toggle.setChecked(baseConfig.enhancements.isEnabled(feature));
                enhancementSwitches.put(feature, toggle);
            }
            if (category == EnhancementConfig.Category.MESSAGES) {
                addDivider(card);
                outgoingPrefixInput = addInput(
                        card,
                        t("发送前缀（最多 64 字符）", "Outgoing prefix (up to 64 characters)"),
                        baseConfig.enhancements.outgoingPrefix
                );
                outgoingSuffixInput = addInput(
                        card,
                        t("发送后缀（最多 64 字符）", "Outgoing suffix (up to 64 characters)"),
                        baseConfig.enhancements.outgoingSuffix
                );
            } else if (category == EnhancementConfig.Category.TRANSFER) {
                addDivider(card);
                downloadParallelismInput = addNumberInput(
                        card,
                        t("下载并发（2–32）", "Download parallelism (2–32)"),
                        baseConfig.enhancements.downloadParallelism
                );
                uploadParallelismInput = addNumberInput(
                        card,
                        t("上传并发（1–16）", "Upload parallelism (1–16)"),
                        baseConfig.enhancements.uploadParallelism
                );
                addInfo(card, t(
                        "加速只调整并发；GramSieve 的卡死检测、退避重试和取消语义保持不变。",
                        "Boosting only adjusts parallelism; GramSieve's stall detection, backoff, and cancellation semantics remain intact."
                ));
            }
        }
    }

    private void buildUntestedFeaturesEntry(LinearLayout container) {
        LinearLayout entry = addCard(container);
        addTitle(entry, t("未测试功能", "Untested features"));
        addInfo(entry, t(
                "隐私、消息、媒体、界面、传输和工具增强尚未逐项完成设备验证。现有开关和配置会完整保留。",
                "Privacy, messaging, media, interface, transfer, and tool enhancements have not completed per-feature device verification. Existing switches and configuration are preserved."
        ));
        TextView action = addInfo(entry, t("点击展开  ›", "Tap to expand  ›"));
        action.setTextColor(accentColor);
        action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        entry.setClickable(true);
        entry.setFocusable(true);

        LinearLayout features = new LinearLayout(context);
        features.setOrientation(LinearLayout.VERTICAL);
        features.setVisibility(View.GONE);
        container.addView(features, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        buildEnhancementCards(features);

        entry.setOnClickListener(view -> {
            boolean expand = features.getVisibility() != View.VISIBLE;
            features.setVisibility(expand ? View.VISIBLE : View.GONE);
            action.setText(expand
                    ? t("点击收起  ‹", "Tap to collapse  ‹")
                    : t("点击展开  ›", "Tap to expand  ›"));
        });
    }

    private void buildLogCard(LinearLayout container) {
        LinearLayout card = addCard(container);
        addTitle(card, t("日志控制台", "Log console"));
        addInfo(card, t(
                "读取 Telegram 宿主的 app-specific gramsieve.log；显示和复制只取有界尾部。",
                "Reads Telegram's app-specific gramsieve.log; display and copy use a bounded tail."
        ));

        LinearLayout terminal = new LinearLayout(context);
        terminal.setOrientation(LinearLayout.VERTICAL);
        terminal.setPadding(dp(12), dp(10), dp(12), dp(10));
        terminal.setBackground(rounded(Color.BLACK, dp(12), Color.rgb(44, 68, 48)));

        TextView terminalHeader = new TextView(context);
        terminalHeader.setText("$ gramsieve log.tail --limit " + LogFileSupport.DEFAULT_TAIL_LINES);
        terminalHeader.setTextColor(Color.rgb(124, 255, 146));
        terminalHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        terminalHeader.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        terminal.addView(terminalHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        logConsoleStatus = new TextView(context);
        logConsoleStatus.setText(t("正在读取…", "Reading…"));
        logConsoleStatus.setTextColor(Color.rgb(150, 190, 155));
        logConsoleStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        logConsoleStatus.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(4);
        statusParams.bottomMargin = dp(8);
        terminal.addView(logConsoleStatus, statusParams);

        logConsoleOutput = new TextView(context);
        logConsoleOutput.setTextColor(Color.rgb(224, 255, 226));
        logConsoleOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        logConsoleOutput.setTypeface(Typeface.MONOSPACE);
        logConsoleOutput.setGravity(Gravity.TOP | Gravity.START);
        logConsoleOutput.setTextIsSelectable(true);
        logConsoleOutput.setHorizontallyScrolling(true);
        logConsoleOutput.setVerticalScrollBarEnabled(true);
        logConsoleOutput.setMovementMethod(ScrollingMovementMethod.getInstance());
        logConsoleOutput.setPadding(dp(2), dp(2), dp(12), dp(2));

        // Use TextView's own scrolling movement instead of nesting a second ScrollView inside
        // the settings ScrollView; this keeps vertical gestures predictable on Telegram web.
        terminal.addView(logConsoleOutput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
        ));
        addView(card, terminal, 8);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button refresh = toolbarButton(t("刷新", "Refresh"));
        Button copy = toolbarButton(t("复制", "Copy"));
        Button export = toolbarButton(t("导出", "Export"));
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(export, new LinearLayout.LayoutParams(0, dp(44), 1f));
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        refresh.setOnClickListener(view -> refreshLogConsole());
        copy.setOnClickListener(view -> copyLogConsole());
        export.setOnClickListener(view -> exportLogConsole());
        refreshLogConsole();
    }

    private void refreshLogConsole() {
        if (logConsoleOutput == null || logConsoleStatus == null) {
            return;
        }
        final int generation = ++logConsoleGeneration;
        logConsoleStatus.setText(t("正在读取…", "Reading…"));
        logConsoleOutput.setText("");
        Context appContext = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        new Thread(() -> {
            LogFileSupport.TailResult result = LogFileSupport.readTail(
                    appContext,
                    LogFileSupport.DEFAULT_TAIL_LINES,
                    LogFileSupport.DEFAULT_TAIL_BYTES
            );
            logConsoleOutput.post(() -> {
                if (generation != logConsoleGeneration || overlay == null) {
                    return;
                }
                logConsoleOutput.setText(result.text);
                if (!result.available) {
                    logConsoleStatus.setText(t(
                            "读取失败：" + result.error,
                            "Read failed: " + result.error
                    ));
                    return;
                }
                logConsoleStatus.setText(
                        "source=" + result.sourcePath
                                + "  bytes=" + result.totalBytes
                                + "  returned=" + result.returnedBytes
                                + "  lines=" + result.lineCount
                                + (result.truncated ? "  [truncated]" : "")
                );
            });
        }, "GramSieve-log-tail").start();
    }

    private void copyLogConsole() {
        if (logConsoleOutput == null || logConsoleOutput.getText() == null
                || logConsoleOutput.getText().length() == 0) {
            Toast.makeText(context, t("暂无可复制日志", "No log tail to copy"), Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(context, t("系统剪贴板不可用", "System clipboard unavailable"), Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("GramSieve log", logConsoleOutput.getText()));
        Toast.makeText(context, t("日志尾部已复制", "Log tail copied"), Toast.LENGTH_SHORT).show();
    }

    private void exportLogConsole() {
        if (logConsoleStatus == null) {
            return;
        }
        final int generation = ++logConsoleGeneration;
        logConsoleStatus.setText(t("正在导出完整日志…", "Exporting complete log…"));
        Context appContext = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        new Thread(() -> {
            LogFileSupport.ExportResult result = LogFileSupport.exportToDownloads(appContext);
            logConsoleStatus.post(() -> {
                if (generation != logConsoleGeneration || overlay == null) {
                    return;
                }
                if (!result.exported) {
                    logConsoleStatus.setText(t(
                            "导出失败：" + result.error,
                            "Export failed: " + result.error
                    ));
                    Toast.makeText(context, t("导出失败：" + result.error,
                            "Export failed: " + result.error), Toast.LENGTH_LONG).show();
                    return;
                }
                String location = result.uri == null ? result.displayName : result.uri.toString();
                logConsoleStatus.setText(t(
                        "已导出：" + result.displayName + "  " + location,
                        "Exported: " + result.displayName + "  " + location
                ));
                Toast.makeText(context, t("日志已导出：" + result.displayName,
                        "Log exported: " + result.displayName), Toast.LENGTH_LONG).show();
            });
        }, "GramSieve-log-export").start();
    }

    private void buildConflictCard(LinearLayout container) {
        LinearLayout card = addCard(container);
        addTitle(card, t("⚠  模块冲突", "⚠  Module conflicts"));
        addInfo(card, t(
                "正在检测已安装模块…",
                "Detecting installed modules…"
        ));
        requestModuleState(card);
    }

    private void requestModuleState(LinearLayout card) {
        int generation = ++moduleProbeGeneration;
        new Thread(() -> {
            RuntimeModuleProbe.Result result = RuntimeModuleProbe.scan(context, module);
            ArrayList<String> names = new ArrayList<>();
            for (ModuleConflictDetector.KnownModule module : result.modules) {
                names.add(module.name());
            }
            Bundle data = new Bundle();
            data.putStringArrayList("installed_modules", names);
            data.putString("source", result.source);
            card.post(() -> completeModuleProbe(generation, card, data));
        }, "GramSieve-module-probe").start();
    }

    private void completeModuleProbe(int generation, LinearLayout card, Bundle data) {
        if (generation != moduleProbeGeneration) {
            return;
        }
        moduleProbeGeneration++;
        if (overlay == null || !card.isAttachedToWindow()) {
            return;
        }
        renderModuleState(card, data);
    }

    private void renderModuleState(LinearLayout card, Bundle data) {
        clearConflictCardBody(card);
        moduleFallbackSwitches.clear();
        EnumSet<ModuleConflictDetector.KnownModule> installed = moduleSet(
                data.getStringArrayList("installed_modules")
        );
        String source = data.getString("source", RuntimeModuleProbe.SOURCE_NONE);
        logSettingsState(source, installed);
        if (installed.isEmpty()) {
            addInfo(card, t(
                    "未检测到已知的 Telegram 增强模块。",
                    "No known Telegram enhancement module was detected."
            ));
            return;
        }

        addInfo(card, t(
                "每个回退开关默认关闭。关闭时使用 GramSieve 方案；开启时 GramSieve 只让出该模块声明的重叠能力。此开关不会替对方模块启用 LSPosed 作用域或内部功能。",
                "Every fallback is off by default. Off uses GramSieve; on makes GramSieve yield only the overlapping capabilities declared for that module. This does not enable the other module's LSPosed scope or internal switches."
        ));
        for (ModuleConflictDetector.KnownModule module : installed) {
            Switch fallback = addSwitch(card, t(
                    "采用 " + module.displayName + " 方案（关闭＝GramSieve）",
                    "Use " + module.displayName + " implementation (off = GramSieve)"
            ));
            fallback.setChecked(baseConfig.enhancements != null
                    && baseConfig.enhancements.isModuleFallbackEnabled(module));
            moduleFallbackSwitches.put(module, fallback);
            addInfo(card, t("GramSieve 将让出：", "GramSieve yields: ")
                    + fallbackCapabilitiesLabel(module));
        }

        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(installed, true);
        if (report.findings.isEmpty()) {
            addInfo(card, t(
                    "已安装模块之间未发现已知功能组重叠。",
                    "No known capability overlap was found among installed modules."
            ));
            return;
        }
        addInfo(card, t(
                "以下按已安装模块保守估计；探针无结果时才尝试普通权限只读 LSPosed 数据库，绝不申请 root。",
                "The following is a conservative installed-package estimate; the LSPosed database is tried read-only only if probes return nothing, and root is never requested."
        ));
        for (ModuleConflictDetector.Finding finding : report.findings) {
            addConflictRow(card, finding);
        }
    }

    private void clearConflictCardBody(LinearLayout card) {
        while (card.getChildCount() > 1) {
            card.removeViewAt(1);
        }
    }

    private static EnumSet<ModuleConflictDetector.KnownModule> moduleSet(List<String> names) {
        EnumSet<ModuleConflictDetector.KnownModule> modules =
                EnumSet.noneOf(ModuleConflictDetector.KnownModule.class);
        if (names == null) {
            return modules;
        }
        for (String name : names) {
            try {
                modules.add(ModuleConflictDetector.KnownModule.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A newer module APK may report a module unknown to this host process.
            }
        }
        return modules;
    }

    private void logSettingsState(
            String source,
            EnumSet<ModuleConflictDetector.KnownModule> modules
    ) {
        StringBuilder names = new StringBuilder();
        for (ModuleConflictDetector.KnownModule module : modules) {
            if (names.length() > 0) {
                names.append(',');
            }
            names.append(module.name());
        }
        ModuleLogger.config(
                "GramSieve",
                "SettingsState host=" + context.getPackageName()
                        + " source=" + source
                        + " modules=" + names
                        + " fallbacks=" + enabledFallbackNames()
                        + " background=" + colorHex(backgroundColor)
                        + " card=" + colorHex(cardColor)
                        + " primary=" + colorHex(primaryTextColor)
                        + " secondary=" + colorHex(secondaryTextColor)
                        + " accent=" + colorHex(accentColor)
                        + " heroStart=" + colorHex(heroStartColor)
                        + " heroEnd=" + colorHex(heroEndColor)
                        + " primaryCard=" + formatRatio(contrastRatio(primaryTextColor, cardColor))
                        + " secondaryCard=" + formatRatio(contrastRatio(secondaryTextColor, cardColor))
                        + " heroStartText=" + formatRatio(contrastRatio(Color.WHITE, heroStartColor))
                        + " heroEndText=" + formatRatio(contrastRatio(Color.WHITE, heroEndColor))
        );
    }

    private static String colorHex(int color) {
        return String.format(Locale.ROOT, "#%08X", color);
    }

    private String enabledFallbackNames() {
        StringBuilder names = new StringBuilder();
        EnhancementConfig config = baseConfig.enhancements == null
                ? new EnhancementConfig()
                : baseConfig.enhancements;
        for (ModuleConflictDetector.KnownModule module : ModuleConflictDetector.KnownModule.values()) {
            Switch toggle = moduleFallbackSwitches.get(module);
            boolean enabled = toggle != null
                    ? toggle.isChecked()
                    : config.isModuleFallbackEnabled(module);
            if (!enabled) {
                continue;
            }
            if (names.length() > 0) {
                names.append(',');
            }
            names.append(module.name());
        }
        return names.toString();
    }

    private String fallbackCapabilitiesLabel(ModuleConflictDetector.KnownModule module) {
        StringBuilder label = new StringBuilder();
        for (ModuleConflictDetector.ConflictKind kind : module.conflictKinds()) {
            if (kind == ModuleConflictDetector.ConflictKind.UI_INJECTION) {
                continue;
            }
            if (label.length() > 0) {
                label.append(" · ");
            }
            label.append(conflictLabel(kind));
        }
        return label.length() == 0 ? t("无已映射能力", "no mapped capabilities") : label.toString();
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.2f", ratio);
    }

    private static int ensureWhiteTextContrast(int color) {
        int adjusted = Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
        for (int i = 0; i < 12 && contrastRatio(Color.WHITE, adjusted) < 4.5d; i++) {
            adjusted = blend(adjusted, Color.BLACK, 0.08f);
        }
        return adjusted;
    }

    private static double contrastRatio(int foreground, int background) {
        int opaqueBackground = Color.rgb(
                Color.red(background),
                Color.green(background),
                Color.blue(background)
        );
        float alpha = Color.alpha(foreground) / 255f;
        int composite = Color.rgb(
                Math.round(Color.red(foreground) * alpha + Color.red(opaqueBackground) * (1f - alpha)),
                Math.round(Color.green(foreground) * alpha + Color.green(opaqueBackground) * (1f - alpha)),
                Math.round(Color.blue(foreground) * alpha + Color.blue(opaqueBackground) * (1f - alpha))
        );
        double lighter = Math.max(relativeLuminance(composite), relativeLuminance(opaqueBackground));
        double darker = Math.min(relativeLuminance(composite), relativeLuminance(opaqueBackground));
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    private static double relativeLuminance(int color) {
        return 0.2126d * linearColor(Color.red(color))
                + 0.7152d * linearColor(Color.green(color))
                + 0.0722d * linearColor(Color.blue(color));
    }

    private static double linearColor(int channel) {
        double value = channel / 255d;
        return value <= 0.04045d
                ? value / 12.92d
                : Math.pow((value + 0.055d) / 1.055d, 2.4d);
    }

    private void addConflictRow(LinearLayout card, ModuleConflictDetector.Finding finding) {
        TextView row = new TextView(context);
        row.setText("• " + conflictLabel(finding.kind) + "  ·  " + severityLabel(finding.severity));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        row.setTextColor(finding.severity == ModuleConflictDetector.Severity.HIGH
                ? Color.rgb(232, 86, 76)
                : primaryTextColor);
        addView(card, row, 4);
    }

    private String conflictLabel(ModuleConflictDetector.ConflictKind kind) {
        switch (kind) {
            case ANTI_RECALL:
                return t("防撤回删除链", "Anti-recall deletion chain");
            case EDIT_HISTORY:
                return t("编辑历史重复记录", "Duplicate edit history");
            case DOWNLOAD_ACCELERATION:
                return t("下载参数覆盖", "Download parameter overwrite");
            case SECRET_MEDIA:
                return t("私密媒体处理", "Secret-media handling");
            case SAVE_RESTRICTION:
                return t("复制/转发限制", "Copy/forward restriction");
            case ADS:
                return t("赞助消息过滤", "Sponsored-message filtering");
            case STORIES:
                return t("Story 界面", "Stories interface");
            case PRIVACY:
                return t("隐私请求拦截", "Privacy request interception");
            case UI_INJECTION:
            default:
                return t("菜单与消息 Cell", "Menus and message cells");
        }
    }

    private String severityLabel(ModuleConflictDetector.Severity severity) {
        switch (severity) {
            case HIGH:
                return t("高风险", "high");
            case MEDIUM:
                return t("中风险", "medium");
            case LOW:
                return t("低风险", "low");
            case NONE:
            default:
                return t("无", "none");
        }
    }

    private void addCategoryStrip(LinearLayout container) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, dp(8), 0);
        for (EnhancementConfig.Category category : EnhancementConfig.Category.values()) {
            TextView chip = new TextView(context);
            chip.setText(categoryIcon(category) + "  " + categoryTitle(category));
            chip.setTextColor(primaryTextColor);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            chip.setPadding(dp(14), dp(9), dp(14), dp(9));
            chip.setBackground(rounded(blend(cardColor, accentColor, 0.10f), dp(18), adjustAlpha(accentColor, 0.28f)));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            chipParams.rightMargin = dp(8);
            row.addView(chip, chipParams);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(12);
        container.addView(scroll, params);
    }

    private void addCategoryHeader(LinearLayout card, EnhancementConfig.Category category) {
        TextView title = addTitle(card, categoryIcon(category) + "  " + categoryTitle(category));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        addInfo(card, categoryDescription(category));
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
                updated.enhancements = collectEnhancementConfig();
                updated.updatedAtEpochMs = System.currentTimeMillis();
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

    private EnhancementConfig collectEnhancementConfig() {
        EnhancementConfig config = baseConfig.enhancements == null
                ? new EnhancementConfig()
                : baseConfig.enhancements.deepCopy();
        for (EnhancementConfig.Feature feature : EnhancementConfig.Feature.values()) {
            Switch toggle = enhancementSwitches.get(feature);
            if (feature.isAvailableInCurrentBuild()) {
                config.setEnabled(feature, toggle != null && toggle.isChecked());
            }
        }
        for (ModuleConflictDetector.KnownModule module : ModuleConflictDetector.KnownModule.values()) {
            Switch toggle = moduleFallbackSwitches.get(module);
            if (toggle != null) {
                config.setModuleFallbackEnabled(module, toggle.isChecked());
            }
        }
        config.downloadParallelism = parseInt(downloadParallelismInput, config.downloadParallelism);
        config.uploadParallelism = parseInt(uploadParallelismInput, config.uploadParallelism);
        config.outgoingPrefix = valueOf(outgoingPrefixInput);
        config.outgoingSuffix = valueOf(outgoingSuffixInput);
        return config.sanitize();
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
        moduleProbeGeneration++;
        logConsoleGeneration++;
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
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(rounded(cardColor, dp(18), strokeColor));
        card.setElevation(dp(2));
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

    private Switch addFeatureSwitch(LinearLayout parent, String text) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(primaryTextColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Switch toggle = new Switch(context);
        toggle.setShowText(false);
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        addView(parent, row, 2);
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

    private EditText addNumberInput(LinearLayout parent, String label, int initialValue) {
        EditText input = addInput(parent, label, Integer.toString(initialValue));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setMaxLines(1);
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

    private String categoryIcon(EnhancementConfig.Category category) {
        switch (category) {
            case PRIVACY:
                return "◈";
            case MESSAGES:
                return "✦";
            case MEDIA:
                return "▶";
            case INTERFACE:
                return "◇";
            case TRANSFER:
                return "⇅";
            case TOOLS:
            default:
                return "⌁";
        }
    }

    private String categoryTitle(EnhancementConfig.Category category) {
        switch (category) {
            case PRIVACY:
                return t("隐私", "Privacy");
            case MESSAGES:
                return t("消息", "Messages");
            case MEDIA:
                return t("媒体", "Media");
            case INTERFACE:
                return t("界面", "Interface");
            case TRANSFER:
                return t("传输", "Transfer");
            case TOOLS:
            default:
                return t("工具", "Tools");
        }
    }

    private String categoryDescription(EnhancementConfig.Category category) {
        switch (category) {
            case PRIVACY:
                return t("控制哪些行为允许发送到 Telegram 服务器。", "Control which activity may be reported to Telegram.");
            case MESSAGES:
                return t("扩展消息显示、发送与定位，同时保留 GramSieve 的规则体系。", "Extend message display, sending, and navigation while preserving GramSieve rules.");
            case MEDIA:
                return t("本地媒体操作与播放行为。", "Local media actions and playback behavior.");
            case INTERFACE:
                return t("精简 Telegram 界面，并补充更直接的信息展示。", "Trim Telegram's interface and expose useful context directly.");
            case TRANSFER:
                return t("下载和上传的并发参数；需要重启 Telegram。", "Download and upload concurrency; restart Telegram after changing.");
            case TOOLS:
            default:
                return t("搜索、成员、会话定位和诊断工具。", "Search, member, navigation, and diagnostic tools.");
        }
    }

    private String featureTitle(EnhancementConfig.Feature feature) {
        switch (feature) {
            case DISABLE_TYPING_STATUS:
                return t("不发送正在输入状态", "Do not send typing status");
            case HIDE_PRIVATE_READ_STATUS:
                return t("隐藏私聊已读状态", "Hide private-chat read status");
            case HIDE_GROUP_READ_STATUS:
                return t("隐藏群聊已读状态", "Hide group read status");
            case HIDE_STORY_VIEW_STATUS:
                return t("隐藏 Story 浏览状态", "Hide Story view status");
            case HIDE_PHONE_NUMBER:
                return t("在资料页隐藏手机号", "Hide phone number in profiles");
            case DISABLE_PERSONALIZED_ADS:
                return t("减少个性化广告追踪", "Reduce personalized-ad tracking");
            case SHOW_EXACT_LAST_SEEN:
                return t("显示精确最后在线时间", "Show exact last-seen time");
            case MARK_READ_AFTER_SEND:
                return t("发送后再标记已读", "Mark read only after sending");
            case SHOW_MESSAGE_ID:
                return t("在时间旁显示消息 ID", "Show message ID beside time");
            case RELOAD_MESSAGE:
                return t("消息重新加载入口", "Message reload action");
            case MESSAGE_AFFIXES:
                return t("发送消息前缀/后缀", "Outgoing message prefix/suffix");
            case AUTO_SAVE_SENT_MESSAGES:
                return t("自动留存已发送消息", "Archive sent messages automatically");
            case EXTENDED_JUMP_TO_MESSAGE:
                return t("增强消息跳转", "Extended jump to message");
            case LOCAL_MESSAGE_DISPLAY:
                return t("本地替换消息显示文字", "Local message display override");
            case SEND_COMMANDS:
                return t("快捷发送命令", "Quick send commands");
            case ALLOW_COPY:
                return t("允许复制受限内容", "Allow copying restricted content");
            case ALLOW_FORWARD:
                return t("允许转发受限内容", "Allow forwarding restricted content");
            case SAVE_VOICE_MESSAGES:
                return t("允许保存语音消息", "Allow saving voice messages");
            case SAVE_SECRET_MEDIA:
                return t("允许保存私密媒体", "Allow saving secret media");
            case KEEP_SECRET_MEDIA:
                return t("防止私密媒体本地销毁", "Keep secret media locally");
            case SAVE_STORIES:
                return t("允许保存 Story", "Allow saving Stories");
            case KEEP_VIDEO_MUTED:
                return t("视频默认保持静音", "Keep videos muted by default");
            case DISABLE_PREMIUM_STICKER_ANIMATION:
                return t("关闭高级贴纸动画", "Disable premium sticker animation");
            case HIDE_SPONSORED_MESSAGES:
                return t("隐藏赞助消息", "Hide sponsored messages");
            case HIDE_PINNED_MESSAGE:
                return t("隐藏聊天置顶横幅", "Hide pinned-message banner");
            case HIDE_SERVICE_STORIES:
                return t("隐藏服务账号 Story", "Hide service-account Stories");
            case HIDE_PREMIUM_STICKER_TAB:
                return t("隐藏高级贴纸页", "Hide premium sticker tab");
            case HIDE_CONTACTS_TAB:
                return t("隐藏联系人页", "Hide contacts tab");
            case HIDE_HOME_ACTION_BUTTONS:
                return t("隐藏首页悬浮操作按钮", "Hide home action buttons");
            case DISABLE_INSTANT_CAMERA:
                return t("关闭输入框即时相机", "Disable instant camera");
            case DISABLE_CHAT_SWIPE_BACK:
                return t("关闭聊天页侧滑返回", "Disable chat swipe-back");
            case DISABLE_PROFILE_SWIPE_BACK:
                return t("关闭资料页侧滑返回", "Disable profile swipe-back");
            case DISABLE_UPDATE_PROMPT:
                return t("关闭 Telegram 更新提示", "Disable Telegram update prompts");
            case FORCE_CHAT_BLUR:
                return t("强制启用聊天模糊效果", "Force chat blur effects");
            case USE_SYSTEM_EMOJI:
                return t("使用系统 Emoji", "Use system emoji");
            case SHOW_ID_IN_PROFILE:
                return t("资料页显示 ID", "Show ID in profiles");
            case SHOW_ID_IN_STATUS_LINE:
                return t("聊天状态栏显示 ID", "Show ID in chat status line");
            case COPY_PROFILE_NAME:
                return t("允许复制资料名称", "Allow copying profile names");
            case SHOW_FULL_NUMBERS:
                return t("人数与计数不缩写", "Show counts without abbreviation");
            case VIEW_TOPIC_AS_MESSAGES:
                return t("话题默认按消息浏览", "View topics as messages by default");
            case FORCE_SNOW_ANIMATION:
                return t("强制雪花动画", "Force snow animation");
            case HIDE_PROTOCOL_ERRORS:
                return t("隐藏底层协议错误弹窗", "Hide protocol error dialogs");
            case DOWNLOAD_BOOST:
                return t("下载并发增强", "Download concurrency boost");
            case UPLOAD_BOOST:
                return t("上传并发增强", "Upload concurrency boost");
            case SHOW_DOWNLOAD_SOURCE:
                return t("显示下载来源", "Show download source");
            case EXTENDED_OFFLINE_SEARCH:
                return t("扩展离线搜索", "Extended offline search");
            case LOCAL_GROUP_MEMBER_LIST:
                return t("本地群成员列表", "Local group member list");
            case HISTORIC_GROUP_MEMBERS:
                return t("记录历史群成员", "Record historic group members");
            case ACCOUNT_CREATION_ESTIMATE:
                return t("估算账号创建时间", "Estimate account creation date");
            case CUSTOM_CALENDAR:
                return t("自定义日历定位消息", "Custom calendar navigation");
            case OPEN_DIALOG_BY_ID:
                return t("按 ID 打开会话", "Open dialog by ID");
            case DATABASE_CORRUPTION_WARNING:
                return t("数据库损坏提醒", "Database corruption warnings");
            case TELEGRAM_DEBUG_MODE:
                return t("Telegram 调试模式", "Telegram debug mode");
            case NETWORK_LOG_CONTROL:
            default:
                return t("Telegram 网络日志", "Telegram network logs");
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

    private static int blend(int from, int to, float amount) {
        float safe = Math.max(0f, Math.min(1f, amount));
        int alpha = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * safe);
        int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * safe);
        int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * safe);
        int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * safe);
        return Color.argb(alpha, red, green, blue);
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

    private static int parseInt(EditText editText, int fallback) {
        try {
            return Integer.parseInt(valueOf(editText).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class RuleInputs {
        EditText matchKeywords;
        EditText matchRegex;
        EditText keepKeywords;
        EditText keepRegex;
    }
}
