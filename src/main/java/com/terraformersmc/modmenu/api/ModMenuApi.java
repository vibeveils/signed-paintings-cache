package com.terraformersmc.modmenu.api;

/**
 * Compile-only stand-in for Mod Menu's real interface (same name and method signature).
 * Excluded from the built jar; at runtime Mod Menu's own class is used.
 */
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}
