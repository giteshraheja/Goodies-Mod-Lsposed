// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;

import java.lang.reflect.Method;

import dev.gitesh.sbmods.Config;

/**
 * Reads temperatures inside SystemUI. SystemUI holds DEVICE_POWER and is exempt from
 * hidden-API limits, so it can query the thermal HAL directly (same data as
 * `dumpsys thermalservice`). Falls back to the battery broadcast, which always works.
 */
public final class Sensors {
    private Object thermal;           // android.os.IThermalService
    private Method getWithType;       // Temperature[] getCurrentTemperaturesWithType(int)
    private Method getValue;          // float Temperature.getValue()
    private int thermalFailures;
    public volatile String lastSource = "-";
    public volatile float lastValue = Float.NaN;

    public float temperature(Context ctx, int type) {
        float v = read(ctx, type);
        lastValue = v;
        return v;
    }

    private float read(Context ctx, int type) {
        if (type != Config.SENSOR_BATTERY && thermalFailures < 5) {
            try {
                float v = thermal(type);
                if (!Float.isNaN(v)) {
                    thermalFailures = 0;
                    lastSource = type == Config.SENSOR_SKIN ? "SKIN" : "AP";
                    return v;
                }
            } catch (Throwable t) {
                thermalFailures++;
                thermal = null;
                Log.w("thermal read failed (" + thermalFailures + "): " + t);
            }
        }
        float b = battery(ctx);
        lastSource = Float.isNaN(b) ? "none" : "BAT";
        return b;
    }

    private float thermal(int type) throws Exception {
        if (thermal == null) {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class)
                    .invoke(null, "thermalservice");
            if (binder == null) throw new IllegalStateException("no thermalservice");
            Class<?> stub = Class.forName("android.os.IThermalService$Stub");
            thermal = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            getWithType = thermal.getClass().getMethod("getCurrentTemperaturesWithType", int.class);
        }
        Object[] arr = (Object[]) getWithType.invoke(thermal, type);
        if (arr == null) return Float.NaN;
        for (Object t : arr) {
            if (t == null) continue;
            if (getValue == null) getValue = t.getClass().getMethod("getValue");
            float v = (Float) getValue.invoke(t);
            if (v > -40f && v < 150f) return v; // skip disconnected sensors (e.g. SUBBAT = 0 / -273)
        }
        return Float.NaN;
    }

    private static float battery(Context ctx) {
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);
        if (i == null) return Float.NaN;
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return t == Integer.MIN_VALUE ? Float.NaN : t / 10f;
    }
}
