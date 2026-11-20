package com.ragemod.client;

/** Sunucudan gelen sinir bilgisi (arayuz icin). */
public final class ClientRageState {
    public static boolean active = false;
    public static float bar = 0f;
    public static float meter = 0f;
    public static int freezeTicks = 0;
    public static int screamCooldown = 0;
    public static boolean grabbing = false;
    public static boolean petRage = false;

    public static void reset() {
        active = false; bar = 0; meter = 0; freezeTicks = 0; screamCooldown = 0; grabbing = false; petRage = false;
    }

    private ClientRageState() {}
}
