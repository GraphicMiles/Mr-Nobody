package com.mrnobody.debug;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The debug panel: a small floating circle (bottom-right) that expands on tap
 * to reveal the error log. The circle shows an error-count badge. This is a
 * developer/tester aid — always on, removable for release builds.
 */
public final class DebugOverlay {

    private final FrameLayout root;
    private final TextView fab;
    private final TextView badge;
    private final LinearLayout panel;
    private boolean expanded = false;
    private boolean collapsed = false;
    private float fabDrift = 0f;   // px the FAB/badge glide down when the nav collapses

    public DebugOverlay(FrameLayout host) {
        Context context = host.getContext();
        this.root = host;

        int dp = (int) (context.getResources().getDisplayMetrics().density);

        // --- expanded panel (hidden by default) ---
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#1b1c21"));
        panel.setPadding(dp * 10, dp * 10, dp * 10, dp * 10);

        TextView head = new TextView(context);
        head.setText("DEBUG");
        head.setTextColor(Color.parseColor("#5aa8e0"));
        head.setTypeface(Typeface.MONOSPACE);
        head.setTextSize(11);
        panel.addView(head);

        // Copy button — copies the full log so testers can paste it anywhere.
        TextView copy = new TextView(context);
        copy.setText("COPY");
        copy.setTextColor(Color.parseColor("#5aa8e0"));
        copy.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        copy.setTextSize(9);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp * 8, dp * 4, dp * 8, dp * 4);
        copy.setBackground(roundedRect(context, "#1b1c21", "#5aa8e0"));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.setMargins(0, dp * 4, 0, dp * 4);
        copy.setLayoutParams(copyLp);
        copy.setOnClickListener(v -> copyLog(context));
        panel.addView(copy);

        ScrollView scroll = new ScrollView(context);
        panel.addView(scroll);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag("log_list");
        scroll.addView(list);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                (int) (dp * 240), (int) (dp * 180));
        panelLp.gravity = Gravity.BOTTOM | Gravity.END;
        panelLp.rightMargin = dp * 12;
        panelLp.bottomMargin = dp * 68;
        panel.setLayoutParams(panelLp);
        panel.setVisibility(android.view.View.GONE);
        host.addView(panel);

        // --- FAB circle ---
        fab = new TextView(context);
        fab.setText("ⓘ");
        fab.setTextColor(Color.parseColor("#5aa8e0"));
        fab.setTextSize(dp * 11);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(roundedCircle(context, "#1b1c21", "#5aa8e0"));

        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp * 40, dp * 40);
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = dp * 12;
        fabLp.bottomMargin = dp * 20;
        fab.setLayoutParams(fabLp);
        fab.setOnClickListener(v -> toggle());
        host.addView(fab);

        // --- badge (error count) ---
        badge = new TextView(context);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(dp * 8);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp * 3, dp * 1, dp * 3, dp * 1);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp * 16);
        badgeLp.gravity = Gravity.BOTTOM | Gravity.END;
        badgeLp.rightMargin = dp * 8;
        badgeLp.bottomMargin = dp * 56;
        badge.setLayoutParams(badgeLp);
        host.addView(badge);

        refresh();
        ErrorLog.addListener(this::refresh);

        // The FAB/badge drift down into the freed space when the nav is hidden.
        fabDrift = dp * 12f;
    }

    /**
     * Glide the FAB (and its badge) down when the bottom nav collapses, and back
     * up when it returns. Smooth, in sync with the toolbar's own animation.
     */
    public void setCollapsed(boolean c) {
        if (collapsed == c) return;
        collapsed = c;
        float target = c ? fabDrift : 0f;
        fab.animate().translationY(target).setDuration(250).start();
        badge.animate().translationY(target).setDuration(250).start();
    }

    private void toggle() {
        expanded = !expanded;
        panel.setVisibility(expanded ? android.view.View.VISIBLE : android.view.View.GONE);
        if (expanded) refresh();
    }

    private void refresh() {
        int count = ErrorLog.count();
        badge.setText(String.valueOf(count));
        badge.setBackground(roundedCircle(root.getContext(),
                count == 0 ? "#7f9c78" : "#b6564f", "transparent"));

        LinearLayout list = root.findViewWithTag("log_list");
        if (list == null) return;
        list.removeAllViews();
        Context context = root.getContext();
        List<String> tail = ErrorLog.tail(30);
        if (tail.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("no errors");
            empty.setTextColor(Color.parseColor("#7f9c78"));
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setTextSize(10);
            list.addView(empty);
            return;
        }
        for (String entry : tail) {
            TextView t = new TextView(context);
            t.setText("✗ " + entry);
            t.setTextColor(Color.parseColor("#b6564f"));
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(9);
            t.setPadding(0, 0, 0, (int) (2 * context.getResources().getDisplayMetrics().density));
            list.addView(t);
        }
    }

    private void copyLog(Context context) {
        StringBuilder sb = new StringBuilder();
        List<String> entries = ErrorLog.tail(200);
        if (entries.isEmpty()) {
            sb.append("(no errors)");
        } else {
            for (String e : entries) sb.append(e).append('\n');
        }
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("MrNobody debug log", sb.toString()));
            Toast.makeText(context, "Debug log copied", Toast.LENGTH_SHORT).show();
        }
    }

    private static android.graphics.drawable.GradientDrawable roundedCircle(
            Context context, String fill, String stroke) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(Color.parseColor(fill));
        if (!"transparent".equals(stroke)) {
            d.setStroke((int) (context.getResources().getDisplayMetrics().density), Color.parseColor(stroke));
        }
        return d;
    }

    private static android.graphics.drawable.GradientDrawable roundedRect(
            Context context, String fill, String stroke) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius((int) (6 * context.getResources().getDisplayMetrics().density));
        d.setColor(Color.parseColor(fill));
        if (!"transparent".equals(stroke)) {
            d.setStroke((int) (context.getResources().getDisplayMetrics().density), Color.parseColor(stroke));
        }
        return d;
    }
}
