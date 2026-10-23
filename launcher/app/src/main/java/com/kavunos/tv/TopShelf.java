package com.kavunos.tv;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The top shelf, as a preview: the wallpaper is never tinted. When the focus rests on a first-row app for a
 * moment, a glass card rises above the row with what is inside: the song or video the app last played (its
 * media session: artwork, title, artist, playing or paused), or for other apps their artwork and when Home last
 * opened them. Moving on hides it at once; nothing redraws while the focus is still.
 */
final class TopShelf {

    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final long DWELL_MS = 900;

    private final Context context;
    private final float density;
    private final FrameLayout view;
    private final LinearLayout card;
    private final ImageView art;
    private final TextView eyebrow, title, subtitle, state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HomeApps.Tile pending;
    private final Runnable reveal = this::reveal;

    TopShelf(Context context, int height, int side, float density) {
        this.context = context;
        this.density = density;
        view = new FrameLayout(context);
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.TOP));

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(26), dp(16));
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(dp(26));
        glass.setColor(0xB81C1C1E);
        glass.setStroke(Math.max(1, dp(1)), 0x1FFFFFFF);
        card.setBackground(glass);
        card.setAlpha(0f);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        cardParams.setMargins(side, 0, side, dp(24));
        view.addView(card, cardParams);

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        art.setClipToOutline(true);
        card.addView(art);

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(20), 0, 0, 0);
        card.addView(text, new LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT));
        eyebrow = line(text, 13, 0x99FFFFFF, R.font.inter_medium);
        title = line(text, 24, 0xFFFFFFFF, R.font.inter_semibold);
        title.setPadding(0, dp(6), 0, 0);
        subtitle = line(text, 16, 0xBFFFFFFF, R.font.inter_regular);
        subtitle.setPadding(0, dp(4), 0, 0);
        state = line(text, 13, 0xFFFFFFFF, R.font.inter_medium);
        state.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(12));
        pill.setColor(0x33FFFFFF);
        state.setBackground(pill);
        LinearLayout.LayoutParams stateParams = (LinearLayout.LayoutParams) state.getLayoutParams();
        stateParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        stateParams.topMargin = dp(12);
    }

    View view() {
        return view;
    }

    /** The focus moved: a first-row tile (previewed after a short rest) or null (hide now). */
    void show(HomeApps.Tile tile) {
        handler.removeCallbacks(reveal);
        pending = tile;
        card.animate().cancel();
        card.animate().alpha(0f).translationY(dp(8)).setDuration(140).setInterpolator(EASE).start();
        if (tile != null) handler.postDelayed(reveal, DWELL_MS);
    }

    private void reveal() {
        HomeApps.Tile tile = pending;
        if (tile == null || !fill(tile)) return;
        card.setTranslationY(dp(14));
        card.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(EASE).start();
    }

    /** Fills the card for the tile; false when there is nothing worth showing. */
    private boolean fill(HomeApps.Tile tile) {
        NowPlaying.Media media = tile.leanback ? NowPlaying.of(context, tile.key) : null;
        if (media != null) {
            eyebrow.setText(tile.label);
            title.setText(media.title);
            subtitle.setText(media.subtitle != null ? media.subtitle : "");
            subtitle.setVisibility(media.subtitle != null ? View.VISIBLE : View.GONE);
            state.setText(context.getString(media.playing ? R.string.shelf_playing : R.string.shelf_paused));
            state.setVisibility(View.VISIBLE);
            if (media.art != null) {
                art.setImageBitmap(media.art);
                size(art, 132, media.art.getWidth() > media.art.getHeight() * 1.2f ? 235 : 132);
            } else {
                art.setImageDrawable(tile.banner != null ? tile.banner : tile.icon);
                size(art, 132, 235);
            }
            return true;
        }
        long opened = tile.leanback ? NowPlaying.lastOpened(context, tile.key) : 0;
        if (opened == 0) return false;
        eyebrow.setText(context.getString(R.string.shelf_last_opened));
        title.setText(tile.label);
        subtitle.setText(DateUtils.getRelativeTimeSpanString(opened, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS));
        subtitle.setVisibility(View.VISIBLE);
        state.setVisibility(View.GONE);
        art.setImageDrawable(tile.banner != null ? tile.banner : tile.icon);
        size(art, 110, 196);
        return true;
    }

    private void size(View v, int heightDp, int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        v.setLayoutParams(params);
    }

    private TextView line(LinearLayout parent, int sp, int color, int font) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(context.getResources().getFont(font));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        parent.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
