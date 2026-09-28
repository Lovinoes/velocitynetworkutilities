package de.lovinoes.networkutilitiescommon.vanish;

/**
 * The see-vanished permission is checked from three separate plugins (vanish, chat,
 * playerinfo). Defining it once here means a typo in one module can't silently desync
 * from the others.
 */
public final class VanishPermissions {

    public static final String SEE_VANISHED = "velocitynetworkvanish.see";

    private VanishPermissions() {
    }
}
