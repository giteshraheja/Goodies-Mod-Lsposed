package dev.gitesh.sbmods.hook;

import android.content.res.Configuration;
import android.content.res.Resources;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Overview space (phone, portrait), all through Samsung's own resources so Samsung still does the layout:
 *  - Grid-style cards (Grid is picked in Good Lock Home Up): task_scene_scale_grid_phone / task_scene_top_grid_phone.
 *    Without Home Up (List style) these are never read, so nothing changes.
 *  - Suggested apps row: 6 columns instead of 4 (Samsung's Fold uses 6) in a lower row.
 * Each value can be tuned with the global setting named next to it (read once; restart the launcher to apply).
 * All off with: settings put global sbmods_recents_big 0.
 */
final class RecentsSize {
    private RecentsSize() {}

    private static final int FLOAT = 0, INT = 1, FRACTION = 2;

    /** {resource name, resource type, kind, value, tuning setting, min, max} */
    private static final Object[][] WANT = {
            {"task_scene_scale_grid_phone", "integer", FLOAT, 0.352f, "sbmods_grid_scale", 0.2f, 0.5f},   // card size, share of the screen (Samsung 0.29)
            {"task_scene_top_grid_phone", "integer", FLOAT, 0.088f, "sbmods_grid_top", 0.03f, 0.3f},      // where the grid starts (Samsung 0.11); the top row moves up 10 dp with it
            {"suggested_apps_default_grid_x", "integer", INT, 6f, "sbmods_sug_cols", 3f, 7f},             // suggested apps per row (Samsung 4)
            {"suggested_apps_height_phone", "fraction", FRACTION, 0.124f, "sbmods_sug_h", 0.05f, 0.2f},    // row height, share of the screen (Samsung 0.124; lower cut the icons off)
            {"row_gap_phone", "integer", FLOAT, 0.03f, "sbmods_grid_rowgap", 0f, 0.1f},                    // gap between the two rows of cards (Samsung 0.06)
            {"page_spacing_grid_phone", "integer", FLOAT, 0.02f, "sbmods_grid_colgap", 0f, 0.1f},          // gap between cards side by side (Samsung 0.04)
            {"task_icon_ratio_grid_phone", "integer", FLOAT, 115f, "sbmods_grid_icon", 80f, 160f},          // card icon, % of Samsung's size (see iconRatio)
    };
    private static int[] ids;
    private static float[] vals;
    private static int on = -1;   // -1 = not read yet

    static void install(ClassLoader cl) {
        install();
        // suggested apps: Samsung builds PhoneLayoutInfo from those resources once; set its grid and row size right after
        try {
            XposedHelpers.findAndHookConstructor("com.honeyspace.ui.common.suggestedapps.presentation.PhoneLayoutInfo", cl,
                    android.content.Context.class, "com.honeyspace.common.interfaces.WindowBounds", "com.honeyspace.sdk.source.CommonSettingsDataSource$ItemSizeLevel",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Resources r = ((android.content.Context) p.args[0]).getResources();
                                if (!ready(r)) return;
                                android.graphics.Point g = (android.graphics.Point) XposedHelpers.getObjectField(p.thisObject, "grid");
                                if (g == null || g.y != 1) return;   // portrait phone row only
                                int cols = Math.round(vals[2]);
                                Object box = XposedHelpers.getObjectField(p.thisObject, "containerLayout");
                                android.util.Size sz = (android.util.Size) XposedHelpers.getObjectField(box, "size");
                                int h = Math.round(sz.getHeight() * vals[3] / 0.124f);   // Samsung's row is 0.124 of the screen
                                XposedHelpers.setObjectField(p.thisObject, "grid", new android.graphics.Point(cols, 1));
                                XposedHelpers.setObjectField(box, "size", new android.util.Size(sz.getWidth(), h));
                            } catch (Throwable t) { Log.w("suggested apps grid: " + t); }
                        }
                    });
        } catch (Throwable t) { Log.w("suggested apps hook: " + t); Log.miss("PhoneLayoutInfo", "more suggested apps in Overview"); }
        // how many suggestions: SuggestedAppsViewModel hard-codes 4 on phones (6 on tablets / Fold main). Its names are obfuscated,
        // so after each (Context) method look for the LiveData that holds that 4 and give it our column count.
        try {
            Class<?> vm = Class.forName("com.honeyspace.ui.honeypots.suggestedapps.viewmodel.SuggestedAppsViewModel", false, cl);
            XC_MethodHook count = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Resources r = ((android.content.Context) p.args[0]).getResources();
                        if (!ready(r)) return;
                        Integer cols = Math.round(vals[2]);
                        for (java.lang.reflect.Field f : p.thisObject.getClass().getDeclaredFields()) {
                            if (!f.getType().getName().equals("androidx.lifecycle.MutableLiveData")) continue;
                            f.setAccessible(true);
                            Object ld = f.get(p.thisObject);
                            if (ld == null) continue;
                            Object v = ld.getClass().getMethod("getValue").invoke(ld);
                            if (v instanceof Integer && (Integer) v == 4) ld.getClass().getMethod("setValue", Object.class).invoke(ld, cols);
                        }
                    } catch (Throwable t) { Log.w("suggested apps count: " + t); }
                }
            };
            int n = 0;
            for (java.lang.reflect.Method m : vm.getDeclaredMethods())
                if (m.getReturnType() == void.class && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == android.content.Context.class) {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, count); n++;
                }
            if (n == 0) Log.miss("SuggestedAppsViewModel count method", "more suggested apps in Overview");
        } catch (Throwable t) { Log.w("suggested apps count hook: " + t); }
        // getInteger / getFraction are tiny and get inlined into Samsung's compiled code: send the callers back to the interpreter so the hooks are seen
        try {
            java.lang.reflect.Method deopt = null;
            for (java.lang.reflect.Method dm : de.robv.android.xposed.XposedBridge.class.getDeclaredMethods()) if (dm.getName().equals("deoptimizeMethod")) deopt = dm;
            if (deopt == null) return;
            for (String cn : new String[]{"com.honeyspace.ui.common.suggestedapps.presentation.PhoneLayoutInfo", "com.honeyspace.common.context.ContextExtensionKt"}) {
                Class<?> c = Class.forName(cn, false, cl);
                for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) deopt.invoke(null, k);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) if (m.getName().equals("getFractionValue")) deopt.invoke(null, m);
            }
        } catch (Throwable t) { Log.w("recents size deopt: " + t); }
    }

    private static void install() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    int id = (Integer) p.args[0];
                    if ((id >>> 24) != 0x7f) return;   // app resources only: cheap exit for everything else
                    Resources r = (Resources) p.thisObject;
                    int at = find(r, id);
                    if (at < 0) return;
                    int kind = (Integer) WANT[at][2];
                    float v = vals[at];
                    String m = p.method.getName();
                    if (m.equals("getFloat") && kind == FLOAT) p.setResult(v);
                    else if (m.equals("getInteger") && kind == INT) p.setResult(Math.round(v));
                    else if (m.equals("getFraction") && kind == FRACTION) p.setResult(v * (Integer) p.args[1]);   // "%" fractions are relative to base
                    else if (m.equals("getValue") && kind == FLOAT) {
                        android.util.TypedValue tv = (android.util.TypedValue) p.args[1];
                        if (tv.type == android.util.TypedValue.TYPE_FLOAT) tv.data = Float.floatToRawIntBits(v);
                    }
                } catch (Throwable ignored) {}
            }
        };
        try { XposedHelpers.findAndHookMethod(Resources.class, "getFloat", int.class, h); } catch (Throwable t) { Log.w("recents size hook: " + t); }
        // Samsung reads the card values as getValue(id, TypedValue, true).getFloat(); getValue(int, ...) is rare (sizes and drawables don't go through it)
        try { XposedHelpers.findAndHookMethod(Resources.class, "getValue", int.class, android.util.TypedValue.class, boolean.class, h); } catch (Throwable t) { Log.w("recents size hook 2: " + t); }
        try { XposedHelpers.findAndHookMethod(Resources.class, "getInteger", int.class, h); } catch (Throwable t) { Log.w("recents size hook 3: " + t); }
        try { XposedHelpers.findAndHookMethod(Resources.class, "getFraction", int.class, int.class, int.class, h); } catch (Throwable t) { Log.w("recents size hook 4: " + t); }
    }

    private static final int ICON = 6;

    /** Samsung sizes card icons from the suggested-app icon (RecentStyler.getIconSize: item size x 0.63); with more columns
     *  those icons shrink, so scale the ratio back by cols/4 and then by the wanted %. */
    private static void iconRatio() {
        if (vals[ICON] > 2f) vals[ICON] = 0.63f * (Math.round(vals[2]) / 4f) * (vals[ICON] / 100f);
    }

    /** Tuned values loaded and the feature on (portrait only). */
    private static boolean ready(Resources r) {
        find(r, 0);
        if (on < 0) find(r, ids[0]);
        return on == 1 && r.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    /** Index into WANT for this id, or -1 (also -1 when switched off or not in portrait). */
    private static int find(Resources r, int id) {
        if (ids == null) {
            int[] i = new int[WANT.length]; float[] v = new float[WANT.length];
            for (int k = 0; k < WANT.length; k++) { i[k] = r.getIdentifier((String) WANT[k][0], (String) WANT[k][1], Entry.LAUNCHER); v[k] = (Float) WANT[k][3]; }
            vals = v; ids = i;
        }
        int at = -1;
        for (int k = 0; k < ids.length; k++) if (ids[k] == id && id != 0) { at = k; break; }
        if (at < 0) return -1;
        if (r.getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) return -1;
        if (on < 0) {
            try {
                android.app.Application app = (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                on = app == null ? 1 : android.provider.Settings.Global.getInt(app.getContentResolver(), "sbmods_recents_big", 1);
                for (int k = 0; app != null && k < WANT.length; k++) {   // fine-tuning without a rebuild; out-of-range values are ignored
                    float f = android.provider.Settings.Global.getFloat(app.getContentResolver(), (String) WANT[k][4], -1f);
                    if (f >= (Float) WANT[k][5] && f <= (Float) WANT[k][6]) vals[k] = f;
                }
            } catch (Throwable t) { on = 1; }
            iconRatio();
        }
        return on == 1 ? at : -1;
    }
}
