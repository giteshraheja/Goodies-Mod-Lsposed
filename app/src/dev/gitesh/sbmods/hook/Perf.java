// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import java.io.BufferedReader;
import java.io.FileReader;

/** Live CPU / GPU numbers straight from the kernel (these files are readable without root). All in MHz. */
final class Perf {
    static final class Snap { int cpuCur, cpuCap, cpuHw, gpuCur, gpuCap, gpuHw, gpuBusy = -1; int[] polCur = new int[0], polHw = new int[0]; }
    private static String[] pol;
    static int[] cpuSteps, gpuSteps;   // the prime cluster's / the GPU's available speeds (MHz)
    /** The cores of each speed group (small, mid, fast), in policy order; read once. */
    static int[][] groups = {{0, 1, 2}, {3, 4, 5, 6}, {7}};
    private static final String G = "/sys/class/kgsl/kgsl-3d0/";

    static int readInt(String f, int div) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l = r.readLine();
            if (l == null) return -1;
            l = l.trim().split("\\s+")[0];
            return (int) (Long.parseLong(l) / div);
        } catch (Throwable t) { return -1; }
    }

    private static int[] steps(String f, int div) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String[] p = r.readLine().trim().split("\\s+");
            int[] v = new int[p.length];
            for (int i = 0; i < p.length; i++) v[i] = (int) (Long.parseLong(p[i]) / div);
            java.util.Arrays.sort(v);
            return v;
        } catch (Throwable t) { return new int[0]; }
    }

    /** The core numbers listed in a policy's related_cpus file ("0 1 2"); empty when unreadable. */
    private static int[] cpus(String f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String[] p = r.readLine().trim().split("[\\s,]+");
            int[] v = new int[p.length];
            for (int i = 0; i < p.length; i++) v[i] = Integer.parseInt(p[i]);
            return v;
        } catch (Throwable t) { return new int[0]; }
    }

    static Snap read() {
        if (pol == null) {
            java.io.File[] fs = new java.io.File("/sys/devices/system/cpu/cpufreq").listFiles((dir, n) -> n.startsWith("policy"));
            if (fs == null) fs = new java.io.File[0];
            java.util.Arrays.sort(fs, (x, y) -> Integer.compare(Integer.parseInt(x.getName().substring(6)), Integer.parseInt(y.getName().substring(6))));
            pol = new String[fs.length];
            for (int i = 0; i < fs.length; i++) pol[i] = fs[i].getPath() + "/";
            cpuSteps = pol.length == 0 ? new int[0] : steps(pol[pol.length - 1] + "scaling_available_frequencies", 1000);
            gpuSteps = steps(G + "devfreq/available_frequencies", 1000000);
            int[][] g = new int[pol.length][];
            boolean ok = pol.length > 0;
            for (int i = 0; i < pol.length && ok; i++) { g[i] = cpus(pol[i] + "related_cpus"); ok = g[i].length > 0; }
            if (ok) groups = g;
        }
        Snap s = new Snap();
        s.polCur = new int[pol.length]; s.polHw = new int[pol.length];
        for (int i = 0; i < pol.length; i++) {
            s.polCur[i] = readInt(pol[i] + "scaling_cur_freq", 1000);
            s.polHw[i] = readInt(pol[i] + "cpuinfo_max_freq", 1000);
            s.cpuCur = Math.max(s.cpuCur, s.polCur[i]);
        }
        if (pol.length > 0) {   // the prime core: the one a limit (or the heat) visibly holds back
            String p = pol[pol.length - 1];
            s.cpuCap = readInt(p + "scaling_max_freq", 1000);
            s.cpuHw = readInt(p + "cpuinfo_max_freq", 1000);
        }
        s.gpuCur = readInt(G + "devfreq/cur_freq", 1000000);
        s.gpuCap = readInt(G + "devfreq/max_freq", 1000000);
        s.gpuHw = gpuSteps.length > 0 ? gpuSteps[gpuSteps.length - 1] : -1;
        s.gpuBusy = readInt(G + "gpu_busy_percentage", 1);
        return s;
    }

    /** Same rule as the helper: the highest step at or below pct % of the hardware maximum. */
    static int pick(int[] steps, int hw, int pct) {
        if (steps == null || steps.length == 0 || hw <= 0) return -1;
        if (pct <= 0) return hw;
        int best = -1;
        for (int v : steps) if ((long) v * 100 <= (long) hw * pct && v > best) best = v;
        return best > 0 ? best : steps[0];
    }

    static String ghz(int mhz) { return String.format(java.util.Locale.US, "%.1f GHz", mhz / 1000f); }
}
