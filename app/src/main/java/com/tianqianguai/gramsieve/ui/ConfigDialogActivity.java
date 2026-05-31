package com.tianqianguai.gramsieve.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.config.AppLocaleManager;
import com.tianqianguai.gramsieve.config.ModuleConfigStore;
import com.tianqianguai.gramsieve.config.PersistentLogStore;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.RuleDraftMatrix;

import java.text.DateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConfigDialogActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_DIALOG_ID = "dialog_id";
    public static final String EXTRA_DIALOG_TITLE = "dialog_title";
    public static final String MODE_GLOBAL = "global";
    public static final String MODE_CHAT = "chat";

    private static final FilterConfig.RuleTarget[] EDITOR_TARGETS = new FilterConfig.RuleTarget[]{
            FilterConfig.RuleTarget.ANY,
            FilterConfig.RuleTarget.TEXT,
            FilterConfig.RuleTarget.CAPTION,
            FilterConfig.RuleTarget.BUTTONS,
            FilterConfig.RuleTarget.SENDER,
            FilterConfig.RuleTarget.CHAT
    };
    private static final FilterConfig.RuleTarget[] CHAT_MODE_EDITOR_TARGETS = new FilterConfig.RuleTarget[]{
            FilterConfig.RuleTarget.ANY,
            FilterConfig.RuleTarget.TEXT,
            FilterConfig.RuleTarget.CAPTION,
            FilterConfig.RuleTarget.BUTTONS,
            FilterConfig.RuleTarget.SENDER
    };

    private AlertDialog dialog;
    private boolean relaunchAfterDismiss;

    private static final class RuleInputBox {
        final TextInputLayout layout;
        final TextInputEditText editText;

        RuleInputBox(TextInputLayout layout, TextInputEditText editText) {
            this.layout = layout;
            this.editText = editText;
        }
    }

    private final class RuleMatrixEditor {
        private final boolean chatMode;
        private final RuleDraftMatrix matchMatrix;
        private final RuleDraftMatrix exclusionMatrix;
        private final RadioGroup kindGroup;
        private final RadioGroup targetGroup;
        private final TextView targetInfo;
        private final RuleInputBox keywordInput;
        private final RuleInputBox regexInput;
        private final Map<FilterConfig.RuleTarget, Integer> targetIds =
                new EnumMap<>(FilterConfig.RuleTarget.class);
        private final int matchKindId = View.generateViewId();
        private final int exclusionKindId = View.generateViewId();

        private boolean editingExclusions;
        private boolean binding;
        private FilterConfig.RuleTarget selectedTarget;

        RuleMatrixEditor(
                LinearLayout container,
                RuleDraftMatrix matchMatrix,
                RuleDraftMatrix exclusionMatrix,
                boolean chatMode
        ) {
            this.chatMode = chatMode;
            this.matchMatrix = matchMatrix;
            this.exclusionMatrix = exclusionMatrix;

            addSectionLabel(container, getString(R.string.dialog_rule_editor_title));
            addInfo(container, getString(chatMode
                    ? R.string.dialog_rule_editor_hint_chat
                    : R.string.dialog_rule_editor_hint_global));

            kindGroup = new RadioGroup(ConfigDialogActivity.this);
            kindGroup.setOrientation(LinearLayout.HORIZONTAL);
            kindGroup.addView(createRadioButton(matchKindId, getString(R.string.dialog_rule_kind_filter)));
            kindGroup.addView(createRadioButton(exclusionKindId, getString(R.string.dialog_rule_kind_keep)));
            addGroup(container, kindGroup);

            addSectionLabel(container, getString(R.string.dialog_target_group_title));
            targetGroup = new RadioGroup(ConfigDialogActivity.this);
            targetGroup.setOrientation(LinearLayout.VERTICAL);
            for (FilterConfig.RuleTarget target : editorTargets(chatMode)) {
                int targetId = View.generateViewId();
                targetIds.put(target, targetId);
                targetGroup.addView(createRadioButton(targetId, targetOptionLabel(target)));
            }
            addGroup(container, targetGroup);

            targetInfo = addInfo(container, "");
            keywordInput = addMultilineInputBox(
                    container,
                    getString(R.string.dialog_input_label_keywords_compact),
                    "",
                    "",
                    1,
                    5
            );
            regexInput = addMultilineInputBox(
                    container,
                    getString(R.string.dialog_input_label_regex_compact),
                    "",
                    "",
                    1,
                    5
            );

            selectedTarget = editorTargets(chatMode)[0];
            editingExclusions = false;
            kindGroup.check(matchKindId);
            targetGroup.check(targetIds.get(selectedTarget));
            bindVisibleFields();

            kindGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (binding) {
                    return;
                }
                saveVisibleFields();
                editingExclusions = checkedId == exclusionKindId;
                updateTargetLabels();
                bindVisibleFields();
            });
            targetGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (binding) {
                    return;
                }
                saveVisibleFields();
                selectedTarget = targetForId(checkedId);
                bindVisibleFields();
            });
        }

        List<FilterConfig.RuleSpec> toMatchRules() {
            saveVisibleFields();
            return exportRules(matchMatrix, chatMode);
        }

        List<FilterConfig.RuleSpec> toExclusionRules() {
            saveVisibleFields();
            return exportRules(exclusionMatrix, chatMode);
        }

        private void saveVisibleFields() {
            if (selectedTarget == null) {
                return;
            }
            RuleDraftMatrix currentMatrix = currentMatrix();
            currentMatrix.set(selectedTarget, FilterConfig.RuleMode.KEYWORD, valueOf(keywordInput.editText));
            currentMatrix.set(selectedTarget, FilterConfig.RuleMode.REGEX, valueOf(regexInput.editText));
            updateTargetLabels();
        }

        private void bindVisibleFields() {
            FilterConfig.RuleTarget target = selectedTarget == null ? editorTargets(chatMode)[0] : selectedTarget;
            RuleDraftMatrix currentMatrix = currentMatrix();
            binding = true;
            keywordInput.editText.setText(currentMatrix.get(target, FilterConfig.RuleMode.KEYWORD));
            regexInput.editText.setText(currentMatrix.get(target, FilterConfig.RuleMode.REGEX));
            keywordInput.layout.setHelperText(keywordHint(target, chatMode));
            regexInput.layout.setHelperText(regexHint(target, chatMode));
            targetInfo.setText(targetHint(target, chatMode));
            binding = false;
        }

        private RuleDraftMatrix currentMatrix() {
            return editingExclusions ? exclusionMatrix : matchMatrix;
        }

        private FilterConfig.RuleTarget targetForId(int checkedId) {
            for (Map.Entry<FilterConfig.RuleTarget, Integer> entry : targetIds.entrySet()) {
                if (entry.getValue() == checkedId) {
                    return entry.getKey();
                }
            }
            return editorTargets(chatMode)[0];
        }

        private void updateTargetLabels() {
            for (FilterConfig.RuleTarget target : editorTargets(chatMode)) {
                Integer targetId = targetIds.get(target);
                if (targetId == null) {
                    continue;
                }
                RadioButton button = targetGroup.findViewById(targetId);
                if (button != null) {
                    button.setText(targetOptionLabel(target));
                }
            }
        }

        private String targetOptionLabel(FilterConfig.RuleTarget target) {
            int count = countRuleLines(currentMatrix().get(target, FilterConfig.RuleMode.KEYWORD))
                    + countRuleLines(currentMatrix().get(target, FilterConfig.RuleMode.REGEX));
            if (count <= 0) {
                return targetLabel(target);
            }
            return getString(R.string.dialog_target_label_with_count, targetLabel(target), count);
        }
    }

    public ConfigDialogActivity() {
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showEditor();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing() && dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private void showEditor() {
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        boolean chatMode = MODE_CHAT.equals(mode);
        long dialogId = getIntent().getLongExtra(EXTRA_DIALOG_ID, Long.MIN_VALUE);
        if (chatMode && dialogId == Long.MIN_VALUE) {
            finish();
            return;
        }

        FilterConfig config = ModuleConfigStore.load(this);
        FilterConfig.ChatRuleSet chatRuleSet = chatMode
                ? config.getOrCreateChatRuleSet(dialogId).deepCopy()
                : null;

        RuleDraftMatrix matchMatrix = RuleDraftMatrix.fromRules(chatMode ? chatRuleSet.rules : config.globalRules);
        RuleDraftMatrix exclusionMatrix = RuleDraftMatrix.fromRules(chatMode ? chatRuleSet.exclusions : config.globalExclusions);

        int padding = dp(20);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding / 2, padding, padding);
        scrollView.addView(
                container,
                new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        );

        MaterialSwitch enabledSwitch = addSwitch(container, getString(R.string.dialog_enabled));
        enabledSwitch.setChecked(chatMode ? chatRuleSet.enabled : config.enabled);

        MaterialSwitch debugLoggingSwitch = null;
        MaterialSwitch excludeChatSwitch = null;
        RadioGroup languageGroup = null;
        RadioGroup actionGroup = null;
        int systemLanguageOptionId = View.NO_ID;
        int englishLanguageOptionId = View.NO_ID;
        int chineseLanguageOptionId = View.NO_ID;

        if (!chatMode) {
            addSectionLabel(container, getString(R.string.dialog_language_group));
            languageGroup = new RadioGroup(this);
            languageGroup.setOrientation(LinearLayout.VERTICAL);
            RadioButton systemLanguageButton = new RadioButton(this);
            systemLanguageOptionId = View.generateViewId();
            systemLanguageButton.setId(systemLanguageOptionId);
            systemLanguageButton.setText(R.string.dialog_language_system);
            RadioButton englishLanguageButton = new RadioButton(this);
            englishLanguageOptionId = View.generateViewId();
            englishLanguageButton.setId(englishLanguageOptionId);
            englishLanguageButton.setText(R.string.dialog_language_english);
            RadioButton chineseLanguageButton = new RadioButton(this);
            chineseLanguageOptionId = View.generateViewId();
            chineseLanguageButton.setId(chineseLanguageOptionId);
            chineseLanguageButton.setText(R.string.dialog_language_simplified_chinese);
            languageGroup.addView(systemLanguageButton);
            languageGroup.addView(englishLanguageButton);
            languageGroup.addView(chineseLanguageButton);
            String selectedLanguageTag = FilterConfig.normalizeAppLanguageTag(config.appLanguageTag);
            if (FilterConfig.APP_LANGUAGE_ENGLISH.equals(selectedLanguageTag)) {
                languageGroup.check(englishLanguageOptionId);
            } else if (FilterConfig.APP_LANGUAGE_SIMPLIFIED_CHINESE.equals(selectedLanguageTag)) {
                languageGroup.check(chineseLanguageOptionId);
            } else {
                languageGroup.check(systemLanguageOptionId);
            }
            addGroup(container, languageGroup);

            debugLoggingSwitch = addSwitch(container, getString(R.string.dialog_debug_logging));
            debugLoggingSwitch.setChecked(config.debugLogging);

            addSectionLabel(container, getString(R.string.dialog_action_group));
            actionGroup = new RadioGroup(this);
            actionGroup.setOrientation(LinearLayout.VERTICAL);
            RadioButton hideButton = new RadioButton(this);
            hideButton.setText(R.string.dialog_action_hide);
            hideButton.setId(android.R.id.button1);
            RadioButton collapseButton = new RadioButton(this);
            collapseButton.setText(R.string.dialog_action_collapse);
            collapseButton.setId(android.R.id.button3);
            RadioButton debugButton = new RadioButton(this);
            debugButton.setText(R.string.dialog_action_mark);
            debugButton.setId(android.R.id.button2);
            actionGroup.addView(hideButton);
            actionGroup.addView(collapseButton);
            actionGroup.addView(debugButton);
            if (config.action == FilterConfig.Action.DEBUG_MARK) {
                actionGroup.check(debugButton.getId());
            } else if (config.action == FilterConfig.Action.COLLAPSE) {
                actionGroup.check(collapseButton.getId());
            } else {
                actionGroup.check(hideButton.getId());
            }
            addGroup(container, actionGroup);
        } else {
            excludeChatSwitch = addSwitch(container, getString(R.string.dialog_exclude_chat));
            excludeChatSwitch.setChecked(chatRuleSet.excludeFromGlobal);
            String chatTitle = getIntent().getStringExtra(EXTRA_DIALOG_TITLE);
            String scopeText = chatTitle == null || chatTitle.isBlank()
                    ? getString(R.string.dialog_scope_info_fallback, Long.toString(dialogId))
                    : getString(R.string.dialog_scope_info, chatTitle);
            addInfo(container, scopeText);
        }

        RuleMatrixEditor ruleEditor = new RuleMatrixEditor(container, matchMatrix, exclusionMatrix, chatMode);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(chatMode ? R.string.dialog_title_chat : R.string.dialog_title_global)
                .setView(scrollView)
                .setNeutralButton(R.string.dialog_view_logs, (d, which) -> showLogViewer())
                .setNegativeButton(R.string.dialog_cancel, (d, which) -> finish())
                .setPositiveButton(R.string.dialog_save, null)
                .setOnDismissListener(d -> {
                    dialog = null;
                    if (relaunchAfterDismiss) {
                        relaunchAfterDismiss = false;
                        startActivity(new Intent(getIntent()));
                    }
                    finish();
                });

        final MaterialSwitch debugLoggingSwitchFinal = debugLoggingSwitch;
        final MaterialSwitch excludeChatSwitchFinal = excludeChatSwitch;
        final RadioGroup languageGroupFinal = languageGroup;
        final RadioGroup actionGroupFinal = actionGroup;
        final int englishLanguageOptionIdFinal = englishLanguageOptionId;
        final int chineseLanguageOptionIdFinal = chineseLanguageOptionId;
        dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            FilterConfig latest = ModuleConfigStore.load(this);
            String previousLanguageTag = FilterConfig.normalizeAppLanguageTag(latest.appLanguageTag);
            if (chatMode) {
                FilterConfig.ChatRuleSet updatedChat = latest.getOrCreateChatRuleSet(dialogId);
                updatedChat.enabled = enabledSwitch.isChecked();
                updatedChat.excludeFromGlobal = excludeChatSwitchFinal != null && excludeChatSwitchFinal.isChecked();
                updatedChat.rules = ruleEditor.toMatchRules();
                updatedChat.exclusions = ruleEditor.toExclusionRules();
                updatedChat.sanitize();
                if (updatedChat.isSemanticallyEmpty()) {
                    latest.chatRules.remove(FilterConfig.chatKey(dialogId));
                }
            } else {
                latest.enabled = enabledSwitch.isChecked();
                latest.debugLogging = debugLoggingSwitchFinal != null && debugLoggingSwitchFinal.isChecked();
                int checkedLanguageId = languageGroupFinal == null ? View.NO_ID : languageGroupFinal.getCheckedRadioButtonId();
                latest.appLanguageTag = selectedLanguageTag(
                        checkedLanguageId,
                        englishLanguageOptionIdFinal,
                        chineseLanguageOptionIdFinal
                );
                int checkedId = actionGroupFinal == null ? android.R.id.button1 : actionGroupFinal.getCheckedRadioButtonId();
                if (checkedId == android.R.id.button2) {
                    latest.action = FilterConfig.Action.DEBUG_MARK;
                } else if (checkedId == android.R.id.button3) {
                    latest.action = FilterConfig.Action.COLLAPSE;
                } else {
                    latest.action = FilterConfig.Action.HIDE;
                }
                latest.globalRules = ruleEditor.toMatchRules();
                latest.globalExclusions = ruleEditor.toExclusionRules();
            }
            latest.sanitize();
            ModuleConfigStore.save(this, latest);
            if (!chatMode && !previousLanguageTag.equals(latest.appLanguageTag)) {
                AppLocaleManager.apply(this, latest.appLanguageTag);
                relaunchAfterDismiss = true;
            }
            Toast.makeText(this, R.string.dialog_saved_toast, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private static FilterConfig.RuleTarget[] editorTargets(boolean chatMode) {
        return chatMode ? CHAT_MODE_EDITOR_TARGETS : EDITOR_TARGETS;
    }

    private MaterialSwitch addSwitch(LinearLayout container, String text) {
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setText(text);
        toggle.setShowText(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(6);
        container.addView(toggle, params);
        return toggle;
    }

    private void addGroup(LinearLayout container, RadioGroup group) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        container.addView(group, params);
    }

    private RadioButton createRadioButton(int id, String text) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(text);
        return button;
    }

    private TextView addSectionLabel(LinearLayout container, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(10);
        params.bottomMargin = dp(4);
        container.addView(label, params);
        return label;
    }

    private TextView addInfo(LinearLayout container, String text) {
        TextView info = new TextView(this);
        info.setText(text);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(6);
        container.addView(info, params);
        return info;
    }

    private RuleInputBox addMultilineInputBox(
            LinearLayout container,
            String title,
            String hint,
            String initialValue,
            int minLines,
            int maxLines
    ) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(title);
        layout.setHelperText(hint);
        TextInputEditText editText = new TextInputEditText(this);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setMinLines(minLines);
        editText.setMaxLines(maxLines);
        editText.setText(initialValue);
        layout.addView(editText, new TextInputLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(4);
        params.bottomMargin = dp(2);
        container.addView(layout, params);
        return new RuleInputBox(layout, editText);
    }

    private String targetLabel(FilterConfig.RuleTarget target) {
        FilterConfig.RuleTarget safeTarget = target == null ? FilterConfig.RuleTarget.ANY : target;
        switch (safeTarget) {
            case TEXT:
                return getString(R.string.dialog_target_label_text);
            case CAPTION:
                return getString(R.string.dialog_target_label_caption);
            case BUTTONS:
                return getString(R.string.dialog_target_label_button);
            case SENDER:
                return getString(R.string.dialog_target_label_sender);
            case CHAT:
                return getString(R.string.dialog_target_label_chat);
            case ANY:
            default:
                return getString(R.string.dialog_target_label_any);
        }
    }

    private String targetScope(FilterConfig.RuleTarget target) {
        FilterConfig.RuleTarget safeTarget = target == null ? FilterConfig.RuleTarget.ANY : target;
        switch (safeTarget) {
            case TEXT:
                return getString(R.string.dialog_rule_target_text);
            case CAPTION:
                return getString(R.string.dialog_rule_target_caption);
            case BUTTONS:
                return getString(R.string.dialog_rule_target_button);
            case SENDER:
                return getString(R.string.dialog_rule_target_sender);
            case CHAT:
                return getString(R.string.dialog_rule_target_chat);
            case ANY:
            default:
                return getString(R.string.dialog_rule_target_any);
        }
    }

    private String targetHint(FilterConfig.RuleTarget target, boolean chatMode) {
        String hint = getString(R.string.dialog_target_hint, targetScope(target));
        if (chatMode && target == FilterConfig.RuleTarget.CHAT) {
            return hint + " " + getString(R.string.dialog_rule_chat_scope_warning);
        }
        return hint;
    }

    private String keywordHint(FilterConfig.RuleTarget target, boolean chatMode) {
        String hint = getString(R.string.dialog_input_hint_keywords, targetScope(target));
        if (chatMode && target == FilterConfig.RuleTarget.CHAT) {
            return hint + " " + getString(R.string.dialog_rule_chat_scope_warning);
        }
        return hint;
    }

    private String regexHint(FilterConfig.RuleTarget target, boolean chatMode) {
        String hint = getString(R.string.dialog_input_hint_regex, targetScope(target));
        if (chatMode && target == FilterConfig.RuleTarget.CHAT) {
            return hint + " " + getString(R.string.dialog_rule_chat_scope_warning);
        }
        return hint;
    }

    private static List<FilterConfig.RuleSpec> exportRules(RuleDraftMatrix source, boolean chatMode) {
        RuleDraftMatrix exported = new RuleDraftMatrix();
        for (FilterConfig.RuleTarget target : editorTargets(chatMode)) {
            exported.set(target, FilterConfig.RuleMode.KEYWORD, source.get(target, FilterConfig.RuleMode.KEYWORD));
            exported.set(target, FilterConfig.RuleMode.REGEX, source.get(target, FilterConfig.RuleMode.REGEX));
        }
        return exported.toRules();
    }

    private static int countRuleLines(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int count = 0;
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static String selectedLanguageTag(int checkedId, int englishOptionId, int chineseOptionId) {
        if (checkedId == englishOptionId) {
            return FilterConfig.APP_LANGUAGE_ENGLISH;
        }
        if (checkedId == chineseOptionId) {
            return FilterConfig.APP_LANGUAGE_SIMPLIFIED_CHINESE;
        }
        return FilterConfig.APP_LANGUAGE_SYSTEM;
    }

    private static String valueOf(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void showLogViewer() {
        PersistentLogStore.LogSnapshot snapshot = PersistentLogStore.load(this);
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);

        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(container);

        if (snapshot.entries.isEmpty()) {
            addInfo(container, getString(R.string.log_viewer_empty));
        } else {
            for (PersistentLogStore.LogEntry entry : snapshot.entries) {
                String time = dateFormat.format(new Date(entry.timestampEpochMs));
                String label = time + " [" + entry.level + "] " + entry.category + "/" + entry.tag;
                TextView header = addInfo(container, label);
                header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                if (!entry.message.isBlank()) {
                    addInfo(container, entry.message);
                }
                if (!entry.throwable.isBlank()) {
                    TextView tv = addInfo(container, entry.throwable);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                }
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_viewer_title)
                .setView(scroll)
                .setNeutralButton(R.string.log_viewer_export, (d, which) -> exportLogs(snapshot, dateFormat))
                .setNegativeButton(R.string.log_viewer_clear, (d, which) -> {
                    PersistentLogStore.clear(this);
                    Toast.makeText(this, R.string.log_viewer_cleared, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.dialog_cancel, null)
                .show();
    }

    private void exportLogs(PersistentLogStore.LogSnapshot snapshot, DateFormat dateFormat) {
        StringBuilder sb = new StringBuilder();
        for (PersistentLogStore.LogEntry entry : snapshot.entries) {
            String time = dateFormat.format(new Date(entry.timestampEpochMs));
            sb.append(time).append(" [").append(entry.level).append("] ");
            sb.append(entry.category).append("/").append(entry.tag).append(": ");
            sb.append(entry.message);
            if (!entry.throwable.isBlank()) {
                sb.append("\n").append(entry.throwable);
            }
            sb.append("\n");
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("GramSieve Logs", sb.toString()));
        }
        Toast.makeText(this, R.string.log_viewer_exported, Toast.LENGTH_SHORT).show();
    }
}
