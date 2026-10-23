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
    private static final int WASH_W = 16, WASH_H = 8;

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
        // A little darker at the very top for the clock.
        View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000, 0x00000000}));
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

    /**
     * A soft wash of the tile's colours: its artwork (or icon) averaged down to a few cells, so no logo survives,
     * deepened a little, and fading to transparent towards the bottom. Stretched over the shelf with filtering it
     * becomes a smooth gradient that melts into the wallpaper.
     */
    private static Bitmap wash(HomeApps.Tile tile) {
        Bitmap cells = Bitmap.createBitmap(WASH_W, WASH_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cells);
        canvas.drawColor(0xFF2C2C2E);
        Drawable art = tile.banner != null ? tile.banner : tile.icon;
        if (art != null) {
            Drawable copy = art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
            copy.setBounds(0, 0, WASH_W, WASH_H);
            copy.draw(canvas);
        }
        int[] px = new int[WASH_W * WASH_H];
        cells.getPixels(px, 0, WASH_W, 0, 0, WASH_W, WASH_H);
        cells.recycle();
        // Average into a 4x2 grid of colours: enough for a left-right and top-bottom shift, no shapes.
        int[] grid = new int[8];
        for (int gy = 0; gy < 2; gy++) {
            for (int gx = 0; gx < 4; gx++) {
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = gy * WASH_H / 2; y < (gy + 1) * WASH_H / 2; y++) {
                    for (int x = gx * WASH_W / 4; x < (gx + 1) * WASH_W / 4; x++) {
                        int c = px[y * WASH_W + x];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                // Deepen: bright banners (white logos) would otherwise glare across the screen.
                grid[gy * 4 + gx] = deepen((int) (r / n), (int) (g / n), (int) (b / n));
            }
        }
        // 4 wide, 6 tall: the two colour rows, then the lower rows fade out to nothing.
        int[] alpha = {255, 255, 235, 170, 80, 0};
        Bitmap out = Bitmap.createBitmap(4, alpha.length, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < alpha.length; y++) {
            for (int x = 0; x < 4; x++) {
                int c = grid[(y == 0 ? 0 : 1) * 4 + x];
                out.setPixel(x, y, (alpha[y] << 24) | (c & 0xFFFFFF));
            }
        }
        return out;
    }

    /** Darker and a touch more saturated, so a colour reads as atmosphere rather than as a flat panel. */
    private static int deepen(int r, int g, int b) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(r, g, b, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.25f);
        hsv[2] = Math.min(hsv[2], 0.62f) * 0.85f;
        return android.graphics.Color.HSVToColor(hsv);
    }
}
