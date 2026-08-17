package com.mrnobody.debug;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
}
