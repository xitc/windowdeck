package io.github.xitc.windowdeck;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Shared settings row with leading icon, title and summary, and a trailing control. */
final class SettingsRow {
    interface Toggle {
        boolean apply(boolean checked);
    }

    final LinearLayout view;
    final TextView value;

    private SettingsRow(LinearLayout view, TextView value) {
        this.view = view;
        this.value = value;
    }

    static SettingsRow action(Context context, int glyph, int color,
            String title, String summary, String valueText) {
        LinearLayout row = content(context, glyph, color, title, summary);
        TextView value = null;
        if (valueText != null) {
            value = SettingsTheme.summary(context, valueText);
            value.setGravity(Gravity.END);
            addTrailing(row, value, 8);
        }
        addTrailing(row, SettingsGlyph.chevron(context), 4);
        return new SettingsRow(row, value);
    }

    static SettingsRow switchRow(Context context, int glyph, int color,
            String title, String summary, boolean checked, Toggle handler) {
        LinearLayout row = content(context, glyph, color, title, summary);
        CouiSwitch toggle = new CouiSwitch(context);
        toggle.setChecked(checked, false);
        addTrailing(row, toggle, 16);
        row.setScreenReaderFocusable(true);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(toggle.isChecked());
            }
        });
        row.setOnClickListener(view -> {
            boolean wanted = !toggle.isChecked();
            if (handler == null || handler.apply(wanted)) {
                toggle.setChecked(wanted, true);
            }
        });
        return new SettingsRow(row, null);
    }

    void setValue(String text) {
        if (value != null) value.setText(text);
    }

    void setEnabled(boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    private static LinearLayout content(Context context, int glyph, int color, String title, String summary) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean hasIcon = glyph >= 0;
        row.setMinimumHeight(Ui.dp(context, hasIcon ? SettingsTheme.ROW_ICON_MIN : SettingsTheme.ROW_MIN));
        int padding = Ui.dp(context, SettingsTheme.ROW_PADDING);
        row.setPadding(padding, 0, padding, 0);
        row.setBackground(SettingsTheme.rowBackground(context));
        row.setClickable(true);
        row.setFocusable(true);
        if (hasIcon) row.addView(SettingsGlyph.icon(context, glyph, color));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        int verticalPadding = Ui.dp(context, 10);
        text.setPaddingRelative(hasIcon ? Ui.dp(context, 16) : 0, verticalPadding,
                Ui.dp(context, 8), verticalPadding);
        text.addView(SettingsTheme.title(context, title));
        if (summary != null && !summary.isEmpty()) text.addView(SettingsTheme.summary(context, summary));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private static void addTrailing(LinearLayout row, View child, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(Ui.dp(row.getContext(), marginDp));
        row.addView(child, params);
    }
}
