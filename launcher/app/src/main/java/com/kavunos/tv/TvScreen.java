package com.kavunos.tv;

import androidx.core.content.res.ResourcesCompat;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's full-screen pages, tvOS style (Wallpapers, Settings): one type scale in Inter, settings
 * rows that turn into a white pill under the focus, and sheets (a centred card of choices, ticked and
 * focused on the current one) or a full-screen view over the page. Subclasses build into {@link #root}.
 */
abstract class TvScreen extends Activity {

    static final int TITLE_SP = 30, BODY_SP = 17, VALUE_SP = 16, CAPTION_SP = 14;

    protected FrameLayout root;
    protected float density;
    protected final List<Row> rows = new ArrayList<>();
    /** The sheet or full-screen view over the page; null when none is open. */
    private View overlay;
    /** The row or item last clicked: where the focus goes back when a sheet closes. */
    protected View returnFocus;

    protected static final class Row {
        final View view;
        final Runnable refresh;

        Row(View view, Runnable refresh) {
            this.view = view;
            this.refresh = refresh;
        }
    }

    protected interface Value {
        String get();
    }

    protected interface Pick {
        void at(int index);
    }

    protected void initScreen() {
        density = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
    }

    /** A settings row: its name, and its current value on the right when {@code value} is given. */
    protected View row(LinearLayout list, String label, Value value, Runnable ok) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(18), 0, dp(18), 0);
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackgroundResource(R.drawable.wall_row_bg);
        TextView name = text(label, BODY_SP, 0, R.font.inter_medium);
        name.setTextColor(getColorStateList(R.color.wall_row_text));
        name.setDuplicateParentStateEnabled(true);
        view.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView shown = null;
        if (value != null) {
            shown = text("", VALUE_SP, 0, R.font.inter_regular);
            shown.setTextColor(getColorStateList(R.color.wall_row_value));
            shown.setDuplicateParentStateEnabled(true);
            view.addView(shown);
        }
        view.setOnClickListener(v -> {
            returnFocus = v;
            ok.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.bottomMargin = dp(2);
        list.addView(view, params);
        TextView finalShown = shown;
        Row row = new Row(view, value == null ? null : () -> finalShown.setText(value.get()));
        rows.add(row);
        if (row.refresh != null) row.refresh.run();
        return view;
    }

    protected View row(LinearLayout list, int label, Value value, Runnable ok) {
        return row(list, getString(label), value, ok);
    }

    protected void refreshRows() {
        for (Row row : rows) if (row.refresh != null) row.refresh.run();
    }

    /** A small caption above a group of rows. */
    protected void section(LinearLayout list, String title) {
        TextView head = text(title, CAPTION_SP, 0x8CFFFFFF, R.font.inter_medium);
        head.setPadding(dp(18), dp(14), dp(18), dp(8));
        list.addView(head);
    }

    protected List<String> labels(int... ids) {
        List<String> out = new ArrayList<>();
        for (int id : ids) out.add(getString(id));
        return out;
    }

    /** A centred card with a title and a list of choices; the current one is ticked and focused. */
    protected void sheet(String title, List<String> labels, int current, Pick pick) {
        LinearLayout card = card(dp(440));
        if (title != null) {
            TextView head = text(title, 15, 0x99FFFFFF, R.font.inter_medium);
            head.setPadding(dp(18), dp(4), dp(18), dp(10));
            card.addView(head);
        }
        View focus = null;
        for (int i = 0; i < labels.size(); i++) {
            int index = i;
            TextView option = text((i == current ? "✓  " : "") + labels.get(i), 18, 0, R.font.inter_regular);
            option.setTextColor(getColorStateList(R.color.wall_row_text));
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(dp(18), 0, dp(18), 0);
            option.setFocusable(true);
            option.setClickable(true);
            option.setBackgroundResource(R.drawable.wall_row_bg);
            option.setOnClickListener(v -> {
                closeOverlay();
                pick.at(index);
            });
            card.addView(option, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            if (i == current || focus == null) focus = option;
        }
        openOverlay(card, focus);
    }

    protected LinearLayout card(int width) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(18), dp(14), dp(14));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(0xFF1C1C1E);
        card.setBackground(shape);
        card.setTag(width);
        return card;
    }

    /** Puts {@code content} over the page: a card (from {@link #card}) centred on a dim, anything else full screen. */
    protected void openOverlay(View content, View focus) {
        closeOverlay();
        FrameLayout scrim = new FrameLayout(this);
        boolean isCard = content.getTag() instanceof Integer;
        scrim.setBackgroundColor(isCard ? 0x99000000 : Color.BLACK);
        scrim.setClickable(true);
        if (isCard) {
            scrim.addView(content, new FrameLayout.LayoutParams((Integer) content.getTag(),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        } else {
            scrim.addView(content, match());
        }
        root.addView(scrim, match());
        overlay = scrim;
        // Keep the focus inside the sheet while it is open.
        for (int i = 0; i < root.getChildCount() - 1; i++) {
            if (root.getChildAt(i) instanceof ViewGroup) {
                ((ViewGroup) root.getChildAt(i)).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
        }
        content.setAlpha(0f);
        content.setScaleX(0.96f);
        content.setScaleY(0.96f);
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        if (focus != null) focus.post(focus::requestFocus);
    }

    protected void closeOverlay() {
        if (overlay == null) return;
        root.removeView(overlay);
        overlay = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (root.getChildAt(i) instanceof ViewGroup) {
                ((ViewGroup) root.getChildAt(i)).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
        }
        onOverlayClosed();
        View back = returnFocus;
        if (back != null && back.isAttachedToWindow()) back.post(back::requestFocus);
    }

    /** After a sheet or full-screen view closes. */
    protected void onOverlayClosed() {
    }

    private boolean fullScreenOverlay() {
        return overlay != null && !(((FrameLayout) overlay).getChildAt(0).getTag() instanceof Integer);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A full-screen view closes on any key; a sheet closes on Back.
        if (overlay != null && event.getAction() == KeyEvent.ACTION_UP
                && (fullScreenOverlay() || event.getKeyCode() == KeyEvent.KEYCODE_BACK)) {
            closeOverlay();
            return true;
        }
        if (fullScreenOverlay() && event.getAction() == KeyEvent.ACTION_DOWN) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) closeOverlay();
        else super.onBackPressed();
    }

    protected boolean overlayOpen() {
        return overlay != null;
    }

    protected TextView text(String value, int sp, int color, int font) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) view.setTextColor(color);
        view.setTypeface(ResourcesCompat.getFont(this, font));
        view.setIncludeFontPadding(false);
        return view;
    }

    protected static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    protected static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    protected int dp(int value) {
        return Math.round(value * density);
    }
}
