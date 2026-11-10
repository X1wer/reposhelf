# SPDX-FileCopyrightText: 2026 RepoShelf contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Release-build szabályok (R8). Debug- és verbose-naplózás eltávolítása (docs/ARCHITECTURE.md 10.5.):
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
