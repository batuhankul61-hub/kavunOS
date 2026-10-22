package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The top shelf, tvOS style: while a first-row app has the focus, the upper part of the screen takes on that
 * app's colours (its banner shrunk to a few pixels and stretched back up, a blur that costs nothing) under its
 * name in large type. Two layers cross-fade on a focus change; between changes nothing redraws.
 */
final class TopShelf {

    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final int WASH_W = 24, WASH_H = 14;

    private final FrameLayout view;
    private final ImageView[] layers = new ImageView[2];
    private final TextView title;
    private int front;
    private String shownKey = "";

    TopShelf(Context context, int height, int side, float density) {
        view = new FrameLayout(context);
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.TOP));
        for (int i = 0; i < 2; i++) {
            ImageView layer = new ImageView(context);
            layer.setScaleType(ImageView.ScaleType.FIT_XY);
            layer.setAlpha(0f);
            view.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            layers[i] = layer;
        }
        // Darker at the top for the clock, fading out at the bottom into the wallpaper.
        View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x40000000, 0x00000000}));
        view.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
        title.setTypeface(context.getResources().getFont(R.font.inter_semibold));
        title.setTextColor(0xFFFFFFFF);
        title.setLetterSpacing(-0.02f);
        title.setShadowLayer(16 * density, 0, 0, 0x66000000);
        title.setSingleLine(true);
        title.setAlpha(0f);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        titleParams.setMargins(side, 0, side, Math.round(28 * density));
        view.addView(title, titleParams);
        view.setAlpha(0f);
    }

    View view() {
        return view;
    }

    /** Shows the shelf for a first-row tile, or hides it with null. */
    void show(HomeApps.Tile tile) {
        if (tile == null) {
            view.animate().alpha(0f).setDuration(260).setInterpolator(EASE).start();
            shownKey = "";
            return;
        }
        view.animate().alpha(1f).setDuration(320).setInterpolator(EASE).start();
        if (tile.key.equals(shownKey)) return;
        shownKey = tile.key;
        ImageView incoming = layers[1 - front];
        ImageView outgoing = layers[front];
        front = 1 - front;
        incoming.setImageBitmap(wash(tile));
        incoming.animate().alpha(1f).setDuration(420).setInterpolator(EASE).start();
        outgoing.animate().alpha(0f).setDuration(420).setInterpolator(EASE).start();
        title.animate().cancel();
        title.setText(tile.label);
        title.setAlpha(0f);
        title.setTranslationY(12 * view.getResources().getDisplayMetrics().density);
        title.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(380).setInterpolator(EASE).start();
    }

    /** The tile's artwork (or icon) drawn at 24x14: stretched over the shelf it is a soft wash of its colours. */
    private static Bitmap wash(HomeApps.Tile tile) {
        Bitmap bitmap = Bitmap.createBitmap(WASH_W, WASH_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xFF1C1C1E);
        Drawable art = tile.banner != null ? tile.banner : tile.icon;
        if (art != null) {
            Drawable copy = art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
            copy.setBounds(0, 0, WASH_W, WASH_H);
            copy.draw(canvas);
        }
        // Calm it: bright banners (white logos) would otherwise glare across half the screen.
        canvas.drawColor(0x4D000000);
        return bitmap;
    }
}
