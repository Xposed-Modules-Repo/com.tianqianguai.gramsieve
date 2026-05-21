package com.tianqianguai.gramsieve.module;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewParent;

import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;

import java.lang.reflect.Method;

final class UiMutation {
    private static final Method SET_MEASURED_DIMENSION_METHOD = lookupSetMeasuredDimensionMethod();

    private UiMutation() {
    }

    static void apply(View view, FilterDecision decision, String messageKey) {
        if (view == null) {
            return;
        }
        ViewState state = ensureState(view);
        view.setTag(R.id.gramsieve_last_message_key, messageKey);
        view.setTag(R.id.gramsieve_last_decision_action, decision.matched ? decision.action : null);
        if (decision.matched) {
            if (decision.action == FilterConfig.Action.DEBUG_MARK) {
                restore(view, state);
                view.setTag(R.id.gramsieve_last_decision_action, decision.action);
                view.setAlpha(0.35f);
                disableInteractions(view);
            } else if (decision.action == FilterConfig.Action.COLLAPSE) {
                collapse(view, state);
            } else {
                hide(view, state);
            }
            return;
        }
        restore(view, state);
    }

    private static ViewState ensureState(View view) {
        Object tag = view.getTag(R.id.gramsieve_view_state);
        if (tag instanceof ViewState) {
            return (ViewState) tag;
        }
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        ViewState state = new ViewState(
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
                view.isEnabled()
        );
        view.setTag(R.id.gramsieve_view_state, state);
        return state;
    }

    private static void hide(View view, ViewState state) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.height = 0;
            if (layoutParams instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) layoutParams;
                margins.topMargin = 0;
                margins.bottomMargin = 0;
                margins.leftMargin = 0;
                margins.rightMargin = 0;
            }
            view.setLayoutParams(layoutParams);
        }
        view.setMinimumHeight(0);
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        disableInteractions(view);
        requestRelayout(view);
    }

    private static void collapse(View view, ViewState state) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            int collapsedHeight = collapsedHeight(view);
            int targetHeight = state.originalHeight > 0 ? Math.min(state.originalHeight, collapsedHeight) : collapsedHeight;
            layoutParams.height = targetHeight;
            if (layoutParams instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) layoutParams;
                margins.topMargin = Math.min(state.originalTopMargin, dp(view, 2));
                margins.bottomMargin = Math.min(state.originalBottomMargin, dp(view, 2));
                margins.leftMargin = state.originalLeftMargin;
                margins.rightMargin = state.originalRightMargin;
            }
            view.setLayoutParams(layoutParams);
        }
        view.setMinimumHeight(0);
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0.18f);
        disableInteractions(view);
        requestRelayout(view);
    }

    private static void restore(View view, ViewState state) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.height = state.originalHeight == 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : state.originalHeight;
            if (layoutParams instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) layoutParams;
                margins.topMargin = state.originalTopMargin;
                margins.bottomMargin = state.originalBottomMargin;
                margins.leftMargin = state.originalLeftMargin;
                margins.rightMargin = state.originalRightMargin;
            }
            view.setLayoutParams(layoutParams);
        }
        view.setMinimumHeight(state.originalMinimumHeight);
        view.setAlpha(state.originalAlpha);
        view.setVisibility(state.originalVisibility);
        view.setClickable(state.originalClickable);
        view.setLongClickable(state.originalLongClickable);
        view.setEnabled(state.originalEnabled);
        requestRelayout(view);
    }

    private static void disableInteractions(View view) {
        view.setClickable(false);
        view.setLongClickable(false);
        view.setEnabled(false);
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
        if (decision != null && decision.matched) {
            return decision.action;
        }
        if (view == null) {
            return null;
        }
        Object tagged = view.getTag(R.id.gramsieve_last_decision_action);
        return tagged instanceof FilterConfig.Action ? (FilterConfig.Action) tagged : null;
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
                boolean originalEnabled
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
        }
    }
}
