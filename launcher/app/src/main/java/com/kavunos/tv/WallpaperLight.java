package com.kavunos.tv;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * How bright a wallpaper is, and what follows from it: the automatic dim that keeps the clock and the dock
 * readable, and which pictures suit the time of day for the "daylight" order (dark ones at night, the middle
 * of the collection around sunrise and sunset, bright ones by day). Pure logic, so it runs in JVM tests.
 */
final class WallpaperLight {

    /** Luma the busiest text area may keep after dimming; white text reads well up to about here. */
    private static final float TARGET = 0.38f;
    static final int MAX_AUTO_DIM = 60;
    /** Minutes either side of sunrise and sunset that count as twilight. */
    private static final int TWILIGHT = 60;
    /** Sunrise and sunset when no place is configured. */
    static final int DEFAULT_SUNRISE = 7 * 60, DEFAULT_SUNSET = 19 * 60;

    static final int NIGHT = 0, TWILIGHT_PHASE = 1, DAY = 2;

    private WallpaperLight() {
    }

    /**
     * Dim in percent for a picture whose top band (clock) and bottom band (title and dock) have these mean
     * lumas, 0..1: enough to bring the brighter band down to TARGET, at most MAX_AUTO_DIM.
     */
    static int autoDim(float top, float bottom) {
        float worst = Math.max(top, bottom);
        if (worst <= TARGET) return 0;
        int dim = Math.round((1f - TARGET / worst) * 100f);
        return Math.max(0, Math.min(MAX_AUTO_DIM, dim));
    }

    /** NIGHT, TWILIGHT_PHASE or DAY for a minute of the day, given sunrise and sunset minutes. */
    static int phase(int minute, int sunrise, int sunset) {
        if (Math.abs(minute - sunrise) <= TWILIGHT || Math.abs(minute - sunset) <= TWILIGHT) return TWILIGHT_PHASE;
        return minute > sunrise && minute < sunset ? DAY : NIGHT;
    }

    /**
     * The pictures for a phase: the collection sorted by brightness and cut in thirds (darkest for NIGHT,
     * the middle for TWILIGHT_PHASE, brightest for DAY). Pictures without a known brightness are left out;
     * with fewer than three known, every picture qualifies.
     */
    static List<File> forPhase(List<File> images, Map<String, Float> luma, int phase) {
        List<File> known = new ArrayList<>();
        for (File image : images) if (luma.containsKey(image.getName())) known.add(image);
        if (known.size() < 3) return new ArrayList<>(images);
        known.sort(Comparator.comparing((File f) -> luma.get(f.getName())).thenComparing(File::getName));
        int third = known.size() / 3;
        int from = phase == NIGHT ? 0 : phase == TWILIGHT_PHASE ? third : known.size() - third;
        int to = phase == NIGHT ? third : phase == TWILIGHT_PHASE ? known.size() - third : known.size();
        return new ArrayList<>(known.subList(from, to));
    }

    /** A random picture for the phase that is not {@code previous}; null for an empty collection. */
    static File pick(List<File> images, Map<String, Float> luma, int phase, String previous, Random random) {
        List<File> candidates = forPhase(images, luma, phase);
        if (candidates.isEmpty()) candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }
}
