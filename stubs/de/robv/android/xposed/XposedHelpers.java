// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package de.robv.android.xposed;
public final class XposedHelpers {
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... p) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader cl, String methodName, Object... p) { return null; }
    public static Class<?> findClass(String n, ClassLoader cl) { return null; }
    public static XC_MethodHook.Unhook findAndHookConstructor(String className, ClassLoader cl, Object... p) { return null; }
    public static Object getObjectField(Object o, String f) { return null; }
    public static void setObjectField(Object o, String f, Object v) {}
}
