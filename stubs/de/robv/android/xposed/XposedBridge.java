// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package de.robv.android.xposed;
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c, String n, XC_MethodHook cb) { return null; }
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member m, XC_MethodHook cb) { return null; }
    public static boolean deoptimizeMethod(java.lang.reflect.Member m) { return false; }
}
