package com.ragemod;

import net.minecraft.entity.Entity;

/** Bir oyuncunun sinir durumu (sadece sunucu tarafinda tutulur). */
public class RageData {
    /** Sinir bari (0-100). Dolunca R ile rage acilir. */
    public float bar = 0f;
    /** Rage modu acik mi? */
    public boolean active = false;
    /** Rage metresi (0-100). Sifirlanirsa rage biter. */
    public float meter = 0f;
    /** Metrenin hic azalmayacagi kalan sure (tick, 20 tick = 1 sn). */
    public int freezeTicks = 0;
    /** Evcil hayvan oldu mu? (rage acilinca 6 dk kilit). */
    public boolean petRage = false;

    public int ticksSinceHit = 9999;
    public int ticksSinceDamaged = 9999;

    public int screamCooldown = 0;
    public int kickCooldown = 0;
    public int throwCooldown = 0;
    public int slamCooldown = 0;

    public float sprintRamp = 0f;
    public Entity grabbed = null;
}
