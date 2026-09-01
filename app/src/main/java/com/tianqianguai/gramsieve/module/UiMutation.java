package com.tianqianguai.gramsieve.module;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewParent;

import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;

import java.lang.reflect.Method;

/**
 * Applies filtering state to a Telegram view without disturbing host-owned layout state.
 *
 * <p>The old implementation captured a baseline for every allowed bind and restored it on every
 * layout callback. Telegram's media-group cells calculate their outer bubble and caption widths
 * in several passes; those unconditional restores could therefore mix two different geometry
 * passes. A baseline now exists only while GramSieve owns a mutation, and the same key/action is a
 * strict no-op while the host still reflects our captured state; a host rebind establishes a new
 * baseline instead.</p>
 */
final class UiMutation {
    private static final Method SET_MEASURED_DIMENSION_METHOD = lookupSetMeasuredDimensionMethod();
    private static final int NOT_CAPTURED = Integer.MIN_VALUE;

    private UiMutation() {
    }

    static MutationResult apply(View view, FilterDecision decision, String messageKey) {
        if (view == null) {
            return MutationResult.noop();
        }
        boolean matched = decision != null && decision.matched;
        FilterConfig.Action requestedAction = matched ? decision.action : null;
        String requestedKey = messageKey == null ? "" : messageKey;
        ViewState state = stateOf(view);
        MutationTransition transition = transitionFor(
                state != null,
                state == null ? "" : state.messageKey,
                state == null ? null : state.action,
                requestedKey,
                requestedAction,
                matched,
                state == null || state.isApplied(view)
        );

        if (!matched) {
            // An unmatched view that was never changed is deliberately untouched: no tags,
            // LayoutParams writes, or requestLayout calls are emitted on the hot path.
            if (transition == MutationTransition.NOOP || state == null) {
                return MutationResult.noop();
            }
            String previousKey = state.messageKey;
            FilterConfig.Action previousAction = state.action;
            boolean changed = restore(view, state);
            clearState(view);
            return MutationResult.restored(previousKey, previousAction, changed);
        }

        if (transition == MutationTransition.NOOP) {
            return MutationResult.noop();
        }

        String previousKey = state == null ? "" : state.messageKey;
        FilterConfig.Action previousAction = state == null ? null : state.action;
        if (state != null && transition == MutationTransition.SWITCHED) {
            restore(view, state);
            clearState(view);
        } else if (state != null && transition == MutationTransition.REBASED) {
            // Telegram changed a value owned by this mutation. The host's current values are the
            // new baseline; restoring the old baseline would overwrite a recycled bind.
            clearState(view);
        }

        ViewState fresh = capture(view, requestedKey, requestedAction);
        view.setTag(R.id.gramsieve_view_state, fresh);
        view.setTag(R.id.gramsieve_last_message_key, requestedKey);
        view.setTag(R.id.gramsieve_last_decision_action, requestedAction);
        boolean changed = mutate(view, fresh, requestedAction);
        return MutationResult.applied(
                transition,
                previousKey,
                requestedKey,
                previousAction,
                requestedAction,
                changed
        );
    }

    private static ViewState stateOf(View view) {
        Object tag = view == null ? null : view.getTag(R.id.gramsieve_view_state);
        return tag instanceof ViewState ? (ViewState) tag : null;
    }

    private static ViewState capture(View view, String messageKey, FilterConfig.Action action) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        return new ViewState(
                view.getVisibility(),
                view.getAlpha(),
                view.getMinimumHeight(),
                layoutParams == null ? ViewGroup.LayoutParams.WRAP_CONTENT : layoutParams.height,
                layoutParams instanceof MarginLayoutParams ? ((MarginLayoutParams) layoutParams).topMargin : 0,
                layoutParams instanceof MarginLayoutParams ? ((MarginLayoutParams) layoutParams).bottomMargin : 0,
                layoutParams instanceof MarginLayoutParams ? ((MarginLayoutParams) layoutParams).leftMargin : 0,
                layoutParams instanceof MarginLayoutParams ? ((MarginLayoutParams) layoutParams).rightMargin : 0,
                view.isClickable(),
                view.isLongClickable(),
                view.isEnabled(),
                messageKey,
                action
        );
    }

    private static boolean mutate(View view, ViewState state, FilterConfig.Action action) {
        if (action == FilterConfig.Action.DEBUG_MARK) {
            boolean changed = setAlphaIfNeeded(view, 0.35f);
            changed |= setClickableIfNeeded(view, false);
            changed |= setLongClickableIfNeeded(view, false);
            changed |= setEnabledIfNeeded(view, false);
            state.captureApplied(view);
            if (changed) {
                requestRelayout(view);
            }
            return changed;
        }
        boolean changed;
        if (action == FilterConfig.Action.COLLAPSE) {
            changed = applyCollapsed(view, state);
        } else {
            changed = applyHidden(view);
        }
        changed |= setMinimumHeightIfNeeded(view, 0);
        changed |= setVisibilityIfNeeded(view, action == FilterConfig.Action.COLLAPSE ? View.VISIBLE : View.GONE);
        changed |= setAlphaIfNeeded(view, action == FilterConfig.Action.COLLAPSE ? 0.18f : 0f);
        changed |= setClickableIfNeeded(view, false);
        changed |= setLongClickableIfNeeded(view, false);
        changed |= setEnabledIfNeeded(view, false);
        state.captureApplied(view);
        if (changed) {
            requestRelayout(view);
        }
        return changed;
    }

    private static boolean applyHidden(View view) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams == null) {
            return false;
        }
        boolean changed = layoutParams.height != targetHeightFor(
                FilterConfig.Action.HIDE,
                layoutParams.height,
                0
        );
        if (changed) {
            layoutParams.height = 0;
        }
        changed |= setMarginsIfNeeded(layoutParams, 0, 0, 0, 0);
        if (changed) {
            view.setLayoutParams(layoutParams);
        }
        return changed;
    }

    private static boolean applyCollapsed(View view, ViewState state) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams == null) {
            return false;
        }
        int collapsedHeight = collapsedHeight(view);
        int targetHeight = targetHeightFor(
                FilterConfig.Action.COLLAPSE,
                state.originalHeight,
                collapsedHeight
        );
        boolean changed = layoutParams.height != targetHeight;
        if (changed) {
            layoutParams.height = targetHeight;
        }
        int top = Math.min(state.originalTopMargin, dp(view, 2));
        int bottom = Math.min(state.originalBottomMargin, dp(view, 2));
        changed |= setMarginsIfNeeded(layoutParams, top, bottom,
                state.originalLeftMargin, state.originalRightMargin);
        if (changed) {
            view.setLayoutParams(layoutParams);
        }
        return changed;
    }

    private static boolean restore(View view, ViewState state) {
        boolean changed = false;
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            boolean layoutChanged = false;
            // Restore only values that still equal the values written by GramSieve. If Telegram
            // has already rebound this recycled View and changed a value, host state wins.
            if (state.appliedHeight != NOT_CAPTURED && layoutParams.height == state.appliedHeight
                    && layoutParams.height != state.originalHeight) {
                layoutParams.height = state.originalHeight;
                layoutChanged = true;
            }
            if (layoutParams instanceof MarginLayoutParams && state.hasAppliedMargins) {
                MarginLayoutParams margins = (MarginLayoutParams) layoutParams;
                if (margins.topMargin == state.appliedTopMargin
                        && margins.topMargin != state.originalTopMargin) {
                    margins.topMargin = state.originalTopMargin;
                    layoutChanged = true;
                }
                if (margins.bottomMargin == state.appliedBottomMargin
                        && margins.bottomMargin != state.originalBottomMargin) {
                    margins.bottomMargin = state.originalBottomMargin;
                    layoutChanged = true;
                }
                if (margins.leftMargin == state.appliedLeftMargin
                        && margins.leftMargin != state.originalLeftMargin) {
                    margins.leftMargin = state.originalLeftMargin;
                    layoutChanged = true;
                }
                if (margins.rightMargin == state.appliedRightMargin
                        && margins.rightMargin != state.originalRightMargin) {
                    margins.rightMargin = state.originalRightMargin;
                    layoutChanged = true;
                }
            }
            if (layoutChanged) {
                view.setLayoutParams(layoutParams);
                changed = true;
            }
        }
        if (state.appliedMinimumHeight == view.getMinimumHeight()
                && state.appliedMinimumHeight != state.originalMinimumHeight) {
            changed |= setMinimumHeightIfNeeded(view, state.originalMinimumHeight);
        }
        if (Float.compare(view.getAlpha(), state.appliedAlpha) == 0
                && Float.compare(state.appliedAlpha, state.originalAlpha) != 0) {
            changed |= setAlphaIfNeeded(view, state.originalAlpha);
        }
        if (view.getVisibility() == state.appliedVisibility
                && state.appliedVisibility != state.originalVisibility) {
            changed |= setVisibilityIfNeeded(view, state.originalVisibility);
        }
        if (view.isClickable() == state.appliedClickable
                && state.appliedClickable != state.originalClickable) {
            changed |= setClickableIfNeeded(view, state.originalClickable);
        }
        if (view.isLongClickable() == state.appliedLongClickable
                && state.appliedLongClickable != state.originalLongClickable) {
            changed |= setLongClickableIfNeeded(view, state.originalLongClickable);
        }
        if (view.isEnabled() == state.appliedEnabled
                && state.appliedEnabled != state.originalEnabled) {
            changed |= setEnabledIfNeeded(view, state.originalEnabled);
        }
        if (changed) {
            requestRelayout(view);
        }
        return changed;
    }

    private static void clearState(View view) {
        view.setTag(R.id.gramsieve_view_state, null);
        view.setTag(R.id.gramsieve_last_message_key, null);
        view.setTag(R.id.gramsieve_last_decision_action, null);
    }

    private static boolean setMarginsIfNeeded(ViewGroup.LayoutParams layoutParams,
                                              int top, int bottom, int left, int right) {
        if (!(layoutParams instanceof MarginLayoutParams)) {
            return false;
        }
        MarginLayoutParams margins = (MarginLayoutParams) layoutParams;
        boolean changed = margins.topMargin != top || margins.bottomMargin != bottom
                || margins.leftMargin != left || margins.rightMargin != right;
        if (changed) {
            margins.topMargin = top;
            margins.bottomMargin = bottom;
            margins.leftMargin = left;
            margins.rightMargin = right;
        }
        return changed;
    }

    private static boolean setMinimumHeightIfNeeded(View view, int value) {
        if (view.getMinimumHeight() == value) {
            return false;
        }
        view.setMinimumHeight(value);
        return true;
    }

    private static boolean setAlphaIfNeeded(View view, float value) {
        if (Float.compare(view.getAlpha(), value) == 0) {
            return false;
        }
        view.setAlpha(value);
        return true;
    }

    private static boolean setVisibilityIfNeeded(View view, int value) {
        if (view.getVisibility() == value) {
            return false;
        }
        view.setVisibility(value);
        return true;
    }

    private static boolean setClickableIfNeeded(View view, boolean value) {
        if (view.isClickable() == value) {
            return false;
        }
        view.setClickable(value);
        return true;
    }

    private static boolean setLongClickableIfNeeded(View view, boolean value) {
        if (view.isLongClickable() == value) {
            return false;
        }
        view.setLongClickable(value);
        return true;
    }

    private static boolean setEnabledIfNeeded(View view, boolean value) {
        if (view.isEnabled() == value) {
            return false;
        }
        view.setEnabled(value);
        return true;
    }

    static void overrideMeasuredHeight(View view, FilterDecision decision) {
        FilterConfig.Action action = measuredAction(view, decision);
        if (view == null || action == null) {
            return;
        }
        if (action == FilterConfig.Action.DEBUG_MARK || SET_MEASURED_DIMENSION_METHOD == null) {
            return;
        }
        int width = Math.max(view.getMeasuredWidth(), view.getWidth());
        int height = action == FilterConfig.Action.COLLAPSE ? collapsedHeight(view) : 0;
        Reflect.invoke(SET_MEASURED_DIMENSION_METHOD, view, width, height);
    }

    private static FilterConfig.Action measuredAction(View view, FilterDecision decision) {
        if (decision != null) {
            // An explicit allow is authoritative for this bind. Do not reuse a stale action from
            // a recycled row before its restore callback has run.
            return decision.matched ? decision.action : null;
        }
        ViewState state = stateOf(view);
        return state == null ? null : state.action;
    }

    private static int collapsedHeight(View view) {
        return Math.round(24f * view.getResources().getDisplayMetrics().density);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void requestRelayout(View view) {
        view.requestLayout();
        ViewParent parent = view.getParent();
        if (parent instanceof View) {
            ((View) parent).requestLayout();
        }
    }

    private static Method lookupSetMeasuredDimensionMethod() {
        try {
            Method method = View.class.getDeclaredMethod("setMeasuredDimension", int.class, int.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /** Pure transition policy used to keep hot-path state changes testable. */
    static MutationTransition transitionFor(
            boolean hasState,
            String previousKey,
            FilterConfig.Action previousAction,
            String nextKey,
            FilterConfig.Action nextAction,
            boolean matched
    ) {
        return transitionFor(
                hasState,
                previousKey,
                previousAction,
                nextKey,
                nextAction,
                matched,
                true
        );
    }

    static MutationTransition transitionFor(
            boolean hasState,
            String previousKey,
            FilterConfig.Action previousAction,
            String nextKey,
            FilterConfig.Action nextAction,
            boolean matched,
            boolean appliedStateIntact
    ) {
        if (!matched) {
            return hasState ? MutationTransition.RESTORED : MutationTransition.NOOP;
        }
        if (hasState && safeEquals(previousKey, nextKey) && previousAction == nextAction) {
            return appliedStateIntact ? MutationTransition.NOOP : MutationTransition.REBASED;
        }
        return hasState ? MutationTransition.SWITCHED : MutationTransition.APPLIED;
    }

    static int targetHeightFor(FilterConfig.Action action, int originalHeight, int collapsedHeight) {
        if (action == FilterConfig.Action.HIDE) {
            return 0;
        }
        if (action == FilterConfig.Action.COLLAPSE) {
            return originalHeight > 0 ? Math.min(originalHeight, collapsedHeight) : collapsedHeight;
        }
        return originalHeight;
    }

    private static boolean safeEquals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    enum MutationTransition {
        NOOP,
        APPLIED,
        SWITCHED,
        REBASED,
        RESTORED
    }

    static final class MutationResult {
        final MutationTransition transition;
        final String previousKey;
        final String key;
        final FilterConfig.Action previousAction;
        final FilterConfig.Action action;
        final boolean changed;

        private MutationResult(MutationTransition transition, String previousKey, String key,
                               FilterConfig.Action previousAction, FilterConfig.Action action,
                               boolean changed) {
            this.transition = transition;
            this.previousKey = previousKey == null ? "" : previousKey;
            this.key = key == null ? "" : key;
            this.previousAction = previousAction;
            this.action = action;
            this.changed = changed;
        }

        static MutationResult noop() {
            return new MutationResult(MutationTransition.NOOP, "", "", null, null, false);
        }

        static MutationResult applied(MutationTransition transition, String previousKey, String key,
                                      FilterConfig.Action previousAction, FilterConfig.Action action,
                                      boolean changed) {
            return new MutationResult(transition, previousKey, key, previousAction, action, changed);
        }

        static MutationResult restored(String previousKey, FilterConfig.Action previousAction,
                                       boolean changed) {
            return new MutationResult(MutationTransition.RESTORED, previousKey, "",
                    previousAction, null, changed);
        }
    }

    private static final class ViewState {
        final int originalVisibility;
        final float originalAlpha;
        final int originalMinimumHeight;
        final int originalHeight;
        final int originalTopMargin;
        final int originalBottomMargin;
        final int originalLeftMargin;
        final int originalRightMargin;
        final boolean originalClickable;
        final boolean originalLongClickable;
        final boolean originalEnabled;
        final String messageKey;
        final FilterConfig.Action action;
        int appliedHeight = NOT_CAPTURED;
        int appliedMinimumHeight;
        int appliedVisibility;
        float appliedAlpha;
        boolean appliedClickable;
        boolean appliedLongClickable;
        boolean appliedEnabled;
        int appliedTopMargin = NOT_CAPTURED;
        int appliedBottomMargin = NOT_CAPTURED;
        int appliedLeftMargin = NOT_CAPTURED;
        int appliedRightMargin = NOT_CAPTURED;
        boolean hasAppliedMargins;

        ViewState(
                int originalVisibility,
                float originalAlpha,
                int originalMinimumHeight,
                int originalHeight,
                int originalTopMargin,
                int originalBottomMargin,
                int originalLeftMargin,
                int originalRightMargin,
                boolean originalClickable,
                boolean originalLongClickable,
                boolean originalEnabled,
                String messageKey,
                FilterConfig.Action action
        ) {
            this.originalVisibility = originalVisibility;
            this.originalAlpha = originalAlpha;
            this.originalMinimumHeight = originalMinimumHeight;
            this.originalHeight = originalHeight;
            this.originalTopMargin = originalTopMargin;
            this.originalBottomMargin = originalBottomMargin;
            this.originalLeftMargin = originalLeftMargin;
            this.originalRightMargin = originalRightMargin;
            this.originalClickable = originalClickable;
            this.originalLongClickable = originalLongClickable;
            this.originalEnabled = originalEnabled;
            this.messageKey = messageKey == null ? "" : messageKey;
            this.action = action;
            this.appliedMinimumHeight = originalMinimumHeight;
            this.appliedVisibility = originalVisibility;
            this.appliedAlpha = originalAlpha;
            this.appliedClickable = originalClickable;
            this.appliedLongClickable = originalLongClickable;
            this.appliedEnabled = originalEnabled;
        }

        void captureApplied(View view) {
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            appliedHeight = layoutParams == null ? NOT_CAPTURED : layoutParams.height;
            appliedMinimumHeight = view.getMinimumHeight();
            appliedVisibility = view.getVisibility();
            appliedAlpha = view.getAlpha();
            appliedClickable = view.isClickable();
            appliedLongClickable = view.isLongClickable();
            appliedEnabled = view.isEnabled();
            if (layoutParams instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) layoutParams;
                appliedTopMargin = margins.topMargin;
                appliedBottomMargin = margins.bottomMargin;
                appliedLeftMargin = margins.leftMargin;
                appliedRightMargin = margins.rightMargin;
                hasAppliedMargins = true;
            } else {
                hasAppliedMargins = false;
            }
        }

        boolean isApplied(View view) {
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            if (appliedHeight != NOT_CAPTURED
                    && (layoutParams == null || layoutParams.height != appliedHeight)) {
                return false;
            }
            if (hasAppliedMargins) {
                if (!(layoutParams instanceof MarginLayoutParams)) {
                    return false;
                }
                MarginLayoutParams margins = (MarginLayoutParams) layoutParams;
                if (margins.topMargin != appliedTopMargin
                        || margins.bottomMargin != appliedBottomMargin
                        || margins.leftMargin != appliedLeftMargin
                        || margins.rightMargin != appliedRightMargin) {
                    return false;
                }
            }
            return view.getMinimumHeight() == appliedMinimumHeight
                    && view.getVisibility() == appliedVisibility
                    && Float.compare(view.getAlpha(), appliedAlpha) == 0
                    && view.isClickable() == appliedClickable
                    && view.isLongClickable() == appliedLongClickable
                    && view.isEnabled() == appliedEnabled;
        }
    }
}
