package com.kavunos.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class WallpaperLightTest {

    @Test
    public void autoDimLeavesDarkPicturesAloneAndCapsBrightOnes() {
        assertEquals(0, WallpaperLight.autoDim(0.2f, 0.3f));
        // The brighter band decides: 0.38 / 0.76 = half, so 50 %.
        assertEquals(50, WallpaperLight.autoDim(0.76f, 0.1f));
        assertEquals(WallpaperLight.MAX_AUTO_DIM, WallpaperLight.autoDim(1f, 1f));
    }

    @Test
    public void phaseFollowsSunriseAndSunset() {
        int rise = 7 * 60, set = 19 * 60;
        assertEquals(WallpaperLight.NIGHT, WallpaperLight.phase(2 * 60, rise, set));
        assertEquals(WallpaperLight.TWILIGHT_PHASE, WallpaperLight.phase(rise + 30, rise, set));
        assertEquals(WallpaperLight.DAY, WallpaperLight.phase(13 * 60, rise, set));
        assertEquals(WallpaperLight.TWILIGHT_PHASE, WallpaperLight.phase(set - 60, rise, set));
        assertEquals(WallpaperLight.NIGHT, WallpaperLight.phase(23 * 60, rise, set));
    }

    @Test
    public void forPhaseCutsTheCollectionInThirdsByBrightness() {
        List<File> images = new ArrayList<>();
        Map<String, Float> luma = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            images.add(new File("p" + i + ".jpg"));
            luma.put("p" + i + ".jpg", i / 10f);
        }
        assertEquals(Arrays.asList("p0.jpg", "p1.jpg", "p2.jpg"), names(WallpaperLight.forPhase(images, luma, WallpaperLight.NIGHT)));
        assertEquals(Arrays.asList("p3.jpg", "p4.jpg", "p5.jpg"), names(WallpaperLight.forPhase(images, luma, WallpaperLight.TWILIGHT_PHASE)));
        assertEquals(Arrays.asList("p6.jpg", "p7.jpg", "p8.jpg"), names(WallpaperLight.forPhase(images, luma, WallpaperLight.DAY)));
    }

    @Test
    public void unmeasuredCollectionsFallBackToEveryPicture() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        assertEquals(2, WallpaperLight.forPhase(images, new HashMap<>(), WallpaperLight.DAY).size());
    }

    @Test
    public void pickAvoidsThePreviousPicture() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        Random random = new Random(1);
        for (int i = 0; i < 20; i++) {
            File picked = WallpaperLight.pick(images, new HashMap<>(), WallpaperLight.DAY, "a.jpg", random);
            assertNotEquals("a.jpg", picked.getName());
        }
        assertTrue(WallpaperLight.pick(new ArrayList<>(), new HashMap<>(), WallpaperLight.DAY, "", random) == null);
    }

    private static List<String> names(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }
}
