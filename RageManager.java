package com.ragemod;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.TridentItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.*;

public final class RageManager {
    private RageManager() {}

    // ================= DENGE AYARLARI =================
    static final int FREEZE_NORMAL = 20 * 120;   // 2 dakika metre azalmaz
    static final int FREEZE_PET = 20 * 360;      // evcil hayvan olduyse 6 dakika
    static final float METER_DRAIN_SECONDS = 60f;// vurmazsan metre 60 sn'de biter
    static final int SCREAM_COOLDOWN = 20 * 20;  // 20 sn
    static final float BLOCK_CHANCE = 0.6f;      // 5'te 3 hasar engellenir
    static final double MAX_SPRINT_BONUS = 0.6;  // max kosu hizi bonusu (%60)
    // ===================================================

    private static final UUID HEALTH_ID = UUID.fromString("6b1e3c2a-1d7e-4c51-9a4f-1a2b3c4d5e01");
    private static final UUID ARMOR_ID = UUID.fromString("6b1e3c2a-1d7e-4c51-9a4f-1a2b3c4d5e02");
    private static final UUID TOUGH_ID = UUID.fromString("6b1e3c2a-1d7e-4c51-9a4f-1a2b3c4d5e03");
    private static final UUID KB_ID = UUID.fromString("6b1e3c2a-1d7e-4c51-9a4f-1a2b3c4d5e04");
    private static final UUID SPEED_ID = UUID.fromString("6b1e3c2a-1d7e-4c51-9a4f-1a2b3c4d5e05");

    public static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.55f, 0f, 0f), 1.6f);
    private static final DustParticleEffect AURA = new DustParticleEffect(new Vector3f(1f, 0.1f, 0.05f), 1.2f);

    private static final Map<UUID, RageData> DATA = new HashMap<>();
    private static final List<ThrownMob> THROWN = new ArrayList<>();
    private static final Map<Entity, Integer> BRAVE_UNTIL = new WeakHashMap<>();

    private static final class ThrownMob {
        final Entity entity;
        final ServerPlayerEntity thrower;
        final boolean big;
        int age = 0;
        ThrownMob(Entity e, ServerPlayerEntity t, boolean big) { this.entity = e; this.thrower = t; this.big = big; }
    }

    public static RageData get(PlayerEntity p) {
        return DATA.computeIfAbsent(p.getUuid(), u -> new RageData());
    }

    public static boolean isRaging(Entity e) {
        return e instanceof ServerPlayerEntity p && get(p).active;
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            tickPlayer(p);
        }
        tickThrownMobs();
    }

    private static void tickPlayer(ServerPlayerEntity p) {
        RageData d = get(p);

        if (!p.isAlive()) {
            if (d.active) endRage(p, d, false);
            d.bar = 0;
            d.petRage = false;
            if (p.age % 10 == 0) sync(p, d);
            return;
        }

        if (d.screamCooldown > 0) d.screamCooldown--;
        if (d.kickCooldown > 0) d.kickCooldown--;
        if (d.throwCooldown > 0) d.throwCooldown--;
        if (d.slamCooldown > 0) d.slamCooldown--;
        d.ticksSinceHit++;
        d.ticksSinceDamaged++;

        if (!d.active) {
            // 20 sn hasar almazsan bar yavasca iner (dolu bar ve evcil hayvan intikami inmez)
            if (d.bar > 0 && d.bar < 100 && !d.petRage && d.ticksSinceDamaged > 400) {
                d.bar = Math.max(0, d.bar - 0.05f);
            }
        } else {
            tickActive(p, d);
        }

        if (p.age % 2 == 0) sync(p, d);
    }

    private static void tickActive(ServerPlayerEntity p, RageData d) {
        ServerWorld w = (ServerWorld) p.getWorld();

        // Metre
        if (d.freezeTicks > 0) {
            d.freezeTicks--;
        } else if (d.ticksSinceHit > 60) {
            d.meter -= 100f / (METER_DRAIN_SECONDS * 20f);
        }
        if (d.meter <= 0) {
            endRage(p, d, true);
            return;
        }

        // Kostukca hizlanma
        if (p.isSprinting()) d.sprintRamp = Math.min(1f, d.sprintRamp + 0.01f);
        else d.sprintRamp = Math.max(0f, d.sprintRamp - 0.05f);
        applySpeed(p, d.sprintRamp);
        if (d.sprintRamp >= 1f && p.isSprinting()) smashWalls(p, w);

        tickGrab(p, d);

        if (p.age % 5 == 0) scareMobs(p, d, w);

        if (p.age % 4 == 0) {
            w.spawnParticles(AURA, p.getX(), p.getY() + 1.0, p.getZ(), 3, 0.4, 0.6, 0.4, 0.0);
        }
    }

    // ------------------------------------------------------------------ actions

    public static void handleAction(ServerPlayerEntity p, int action, int arg) {
        if (!p.isAlive()) return;
        RageData d = get(p);
        switch (action) {
            case RageNet.A_SCREAM -> {
                if (!d.active) {
                    if (d.bar >= 100f) activate(p, d);
                    else p.sendMessage(Text.literal("Henüz yeterince sinirli değilsin... (" + (int) d.bar + "%)").formatted(Formatting.GRAY), true);
                } else if (d.screamCooldown <= 0) {
                    scream(p, d);
                } else {
                    p.sendMessage(Text.literal("Bağırmak için " + (d.screamCooldown / 20 + 1) + " sn bekle").formatted(Formatting.RED), true);
                }
            }
            case RageNet.A_DEACTIVATE -> { if (d.active) endRage(p, d, true); }
            case RageNet.A_KICK -> { if (d.active) kick(p, d); }
            case RageNet.A_GRAB -> { if (d.active) grab(p, d); }
            case RageNet.A_THROW_MOB -> { if (d.active) throwMob(p, d); }
            case RageNet.A_THROW_TOOL -> { if (d.active) throwTool(p, d); }
            case RageNet.A_THROW_BLOCK -> { if (d.active) throwBlock(p, d, arg); }
            case RageNet.A_SUPERJUMP -> {
                if (d.active) {
                    ServerWorld w = (ServerWorld) p.getWorld();
                    w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 15, 0.5, 0.1, 0.5, 0.05);
                    w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.4f, 1.6f);
                }
            }
            case RageNet.A_SLAM -> { if (d.active && d.slamCooldown <= 0) slam(p, d); }
            case RageNet.A_DASH -> {
                if (d.active) {
                    ServerWorld w = (ServerWorld) p.getWorld();
                    w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5, p.getZ(), 10, 0.3, 0.3, 0.3, 0.02);
                    w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1f, 0.6f);
                }
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ activate / end

    private static void activate(ServerPlayerEntity p, RageData d) {
        d.active = true;
        d.meter = 100f;
        d.freezeTicks = d.petRage ? FREEZE_PET : FREEZE_NORMAL;
        d.petRage = false;
        d.bar = 0f;
        d.ticksSinceHit = 0;
        d.sprintRamp = 0f;

        addMod(p, EntityAttributes.GENERIC_MAX_HEALTH, HEALTH_ID, 1.0, EntityAttributeModifier.Operation.MULTIPLY_TOTAL);
        addMod(p, EntityAttributes.GENERIC_ARMOR, ARMOR_ID, 20.0, EntityAttributeModifier.Operation.ADDITION);
        addMod(p, EntityAttributes.GENERIC_ARMOR_TOUGHNESS, TOUGH_ID, 12.0, EntityAttributeModifier.Operation.ADDITION);
        addMod(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, KB_ID, 0.6, EntityAttributeModifier.Operation.ADDITION);
        p.setHealth(p.getMaxHealth());

        p.networkHandler.sendPacket(new TitleFadeS2CPacket(3, 25, 10));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("RAGE").formatted(Formatting.DARK_RED, Formatting.BOLD)));

        scream(p, d);
    }

    private static void endRage(ServerPlayerEntity p, RageData d, boolean debuff) {
        d.active = false;
        d.meter = 0f;
        d.freezeTicks = 0;
        d.sprintRamp = 0f;
        d.grabbed = null;
        removeAllMods(p);
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());

        if (debuff && p.isAlive()) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 20 * 10, 0));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 15, 1));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 20 * 10, 0));
            p.sendMessage(Text.literal("Sinirin geçti... kendini bitkin hissediyorsun.").formatted(Formatting.DARK_GRAY), true);
            p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_BREATH, SoundCategory.PLAYERS, 1f, 0.6f);
        }
        sync(p, d);
    }

    /** Giris/cikis/respawn: her seyi temizle. */
    public static void reset(ServerPlayerEntity p) {
        RageData d = get(p);
        d.active = false;
        d.meter = 0;
        d.freezeTicks = 0;
        d.grabbed = null;
        d.sprintRamp = 0;
        removeAllMods(p);
    }

    // ------------------------------------------------------------------ scream

    private static void scream(ServerPlayerEntity p, RageData d) {
        ServerWorld w = (ServerWorld) p.getWorld();
        double x = p.getX(), y = p.getY() + 1.0, z = p.getZ();

        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 2.0f, 0.7f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.SONIC_BOOM, x, y, z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 48; i++) {
            double a = i * (Math.PI * 2 / 48);
            w.spawnParticles(ParticleTypes.CLOUD, x, y - 0.5, z, 0, Math.cos(a), 0.05, Math.sin(a), 0.9);
        }

        // 8 blok icindeki canlilara hasar + savurma
        Box box = p.getBoundingBox().expand(8);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, box,
                e -> e != p && e.isAlive() && e != d.grabbed && !isOwnedBy(e, p) && e.squaredDistanceTo(p) <= 64)) {
            e.damage(p.getDamageSources().playerAttack(p), 6f);
            Vec3d push = e.getPos().subtract(p.getPos()).normalize();
            e.takeKnockback(1.6, -push.x, -push.z);
            e.addVelocity(0, 0.3, 0);
            e.velocityModified = true;
        }

        // Sadece cam ve mesaleleri kir (8 blok)
        BlockPos c = p.getBlockPos();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    if (dx * dx + dy * dy + dz * dz > 64) continue;
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    BlockState st = w.getBlockState(m);
                    if (st.isAir()) continue;
                    String path = Registries.BLOCK.getId(st.getBlock()).getPath();
                    if (path.contains("glass") || path.contains("torch")) {
                        w.breakBlock(m, path.contains("torch"), p);
                    }
                }
            }
        }

        // Bufflar
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 20 * 20, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 20 * 20, 3));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 20 * 20, 0));

        // Tutulan canli bagirisa maruz kalirsa
        if (d.grabbed instanceof LivingEntity le) {
            le.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 20 * 10, 0));
            le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 * 10, 0));
            blood(w, le.getX(), le.getY() + le.getHeight() / 2, le.getZ(), 15);
        }

        d.meter = Math.min(100f, d.meter + 12f);
        d.ticksSinceHit = 0;
        d.screamCooldown = SCREAM_COOLDOWN;
    }

    // ------------------------------------------------------------------ kick

    private static void kick(ServerPlayerEntity p, RageData d) {
        if (d.kickCooldown > 0) return;
        d.kickCooldown = 14;
        p.swingHand(Hand.MAIN_HAND, true);
        Entity t = raycastEntity(p, 4.5);
        ServerWorld w = (ServerWorld) p.getWorld();
        if (t instanceof LivingEntity le && t != d.grabbed) {
            Vec3d look = p.getRotationVec(1f);
            le.damage(p.getDamageSources().playerAttack(p), 7f);
            le.takeKnockback(3.0, -look.x, -look.z);
            le.addVelocity(0, 0.35, 0);
            le.velocityModified = true;
            w.playSound(null, le.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.2f, 0.7f);
            w.playSound(null, le.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1f, 0.8f);
        } else {
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 1f, 0.8f);
        }
    }

    // ------------------------------------------------------------------ grab / throw

    private static void grab(ServerPlayerEntity p, RageData d) {
        if (d.grabbed != null) return;
        Entity t = raycastEntity(p, 5.0);
        if (t instanceof LivingEntity && !(t instanceof PlayerEntity)
                && !(t instanceof EnderDragonEntity) && !(t instanceof WitherEntity)) {
            d.grabbed = t;
            ServerWorld w = (ServerWorld) p.getWorld();
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1f, 0.6f);
            blood(w, t.getX(), t.getY() + t.getHeight() / 2, t.getZ(), 8);
        }
    }

    private static void tickGrab(ServerPlayerEntity p, RageData d) {
        Entity e = d.grabbed;
        if (e == null) return;
        if (!e.isAlive() || e.isRemoved() || e.getWorld() != p.getWorld() || e.squaredDistanceTo(p) > 144) {
            d.grabbed = null;
            return;
        }
        Vec3d look = p.getRotationVec(1f);
        Vec3d pos = p.getEyePos().add(look.multiply(2.2)).subtract(0, e.getHeight() / 2, 0);
        e.setVelocity(Vec3d.ZERO);
        e.fallDistance = 0;
        e.setPosition(pos.x, pos.y, pos.z);
        e.velocityModified = true;
    }

    private static void throwMob(ServerPlayerEntity p, RageData d) {
        Entity e = d.grabbed;
        if (e == null) return;
        d.grabbed = null;
        boolean big = !p.isOnGround(); // havadayken firlatirsan daha buyuk patlama
        Vec3d look = p.getRotationVec(1f);
        double speed = big ? 3.2 : 2.4;
        e.setVelocity(look.multiply(speed).add(0, 0.2, 0));
        e.velocityModified = true;
        THROWN.add(new ThrownMob(e, p, big));
        p.swingHand(Hand.MAIN_HAND, true);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.8f, 0.6f);
    }

    private static void tickThrownMobs() {
        Iterator<ThrownMob> it = THROWN.iterator();
        while (it.hasNext()) {
            ThrownMob tm = it.next();
            tm.age++;
            Entity e = tm.entity;
            if (e.isRemoved() || !(e.getWorld() instanceof ServerWorld w)) {
                it.remove();
                continue;
            }
            w.spawnParticles(BLOOD, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), 2, 0.2, 0.2, 0.2, 0);

            boolean hitEntity = !w.getOtherEntities(e, e.getBoundingBox().expand(0.4),
                    o -> o instanceof LivingEntity && o != tm.thrower && o.isAlive()).isEmpty();

            if ((tm.age > 3 && (e.isOnGround() || e.horizontalCollision || hitEntity || e.isTouchingWater()))
                    || tm.age > 80 || !e.isAlive()) {
                Vec3d pos = e.getPos();
                if (e.isAlive()) e.damage(tm.thrower.getDamageSources().playerAttack(tm.thrower), tm.big ? 20f : 12f);
                w.createExplosion(tm.thrower, pos.x, pos.y + 0.5, pos.z, tm.big ? 4.5f : 2.5f, RageMod.EXPLOSION_TYPE);
                blood(w, pos.x, pos.y + 0.5, pos.z, tm.big ? 80 : 45);
                it.remove();
            }
        }
    }

    private static boolean isThrowableTool(ItemStack s) {
        return s.getItem() instanceof ToolItem || s.getItem() instanceof TridentItem;
    }

    private static void throwTool(ServerPlayerEntity p, RageData d) {
        if (d.throwCooldown > 0) return;
        ItemStack stack = p.getMainHandStack();
        if (!isThrowableTool(stack)) return;
        float dmg = stack.getItem() instanceof AxeItem ? 14f
                : (stack.getItem() instanceof SwordItem || stack.getItem() instanceof TridentItem) ? 12f : 9f;
        ItemStack thrown = stack.copy();
        if (!p.getAbilities().creativeMode) p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);

        RageThrownItemEntity proj = new RageThrownItemEntity(p.getWorld(), p, thrown, false, dmg);
        Vec3d look = p.getRotationVec(1f);
        proj.setVelocity(look.x, look.y, look.z, 2.4f, 0.5f);
        p.getWorld().spawnEntity(proj);
        d.throwCooldown = 10;
        p.swingHand(Hand.MAIN_HAND, true);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_THROW, SoundCategory.PLAYERS, 1f, 0.8f);
    }

    private static void throwBlock(ServerPlayerEntity p, RageData d, int chargeTicks) {
        if (d.throwCooldown > 0) return;
        ItemStack stack = p.getMainHandStack();
        if (!(stack.getItem() instanceof BlockItem)) return;
        float charge = MathHelper.clamp(chargeTicks / 30f, 0f, 1f);
        ItemStack one = stack.copy();
        one.setCount(1);
        if (!p.getAbilities().creativeMode) stack.decrement(1);

        RageThrownItemEntity proj = new RageThrownItemEntity(p.getWorld(), p, one, true, 1.5f + 2.5f * charge);
        Vec3d look = p.getRotationVec(1f);
        proj.setVelocity(look.x, look.y, look.z, 1.2f + 1.4f * charge, 1f);
        p.getWorld().spawnEntity(proj);
        d.throwCooldown = 6;
        p.swingHand(Hand.MAIN_HAND, true);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 1f, 0.5f);
    }

    // ------------------------------------------------------------------ slam

    private static void slam(ServerPlayerEntity p, RageData d) {
        d.slamCooldown = 20;
        ServerWorld w = (ServerWorld) p.getWorld();
        Vec3d pos = p.getPos();
        w.createExplosion(p, pos.x, pos.y, pos.z, 3.0f, RageMod.EXPLOSION_TYPE);

        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
        if (bolt != null) {
            bolt.refreshPositionAfterTeleport(pos);
            bolt.setCosmetic(true); // sadece goruntu: yangin ve hasar yok
            w.spawnEntity(bolt);
        }
        for (int i = 0; i < 36; i++) {
            double a = i * (Math.PI * 2 / 36);
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, w.getBlockState(p.getBlockPos().down())),
                    pos.x, pos.y + 0.1, pos.z, 0, Math.cos(a), 0.3, Math.sin(a), 0.6);
        }
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(6),
                e -> e != p && e.isAlive() && !isOwnedBy(e, p))) {
            e.damage(p.getDamageSources().playerAttack(p), 6f);
            e.addVelocity(0, 0.9, 0);
            e.velocityModified = true;
        }
    }

    // ------------------------------------------------------------------ passive stuff

    private static void applySpeed(ServerPlayerEntity p, float ramp) {
        EntityAttributeInstance inst = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (inst == null) return;
        double target = MAX_SPRINT_BONUS * ramp;
        EntityAttributeModifier cur = inst.getModifier(SPEED_ID);
        if (cur != null && Math.abs(cur.getValue() - target) < 0.03) return;
        if (cur != null) inst.removeModifier(SPEED_ID);
        if (target > 0.001) {
            inst.addTemporaryModifier(new EntityAttributeModifier(SPEED_ID, "rage_speed", target,
                    EntityAttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    private static void smashWalls(ServerPlayerEntity p, ServerWorld w) {
        Vec3d dir = Vec3d.fromPolar(0, p.getYaw());
        Box box = p.getBoundingBox().offset(dir.x * 0.8, 0, dir.z * 0.8).contract(0.05);
        BlockPos.Mutable m = new BlockPos.Mutable();
        boolean broke = false;
        for (int x = MathHelper.floor(box.minX); x <= MathHelper.floor(box.maxX); x++) {
            for (int y = MathHelper.floor(box.minY); y <= MathHelper.floor(box.maxY); y++) {
                for (int z = MathHelper.floor(box.minZ); z <= MathHelper.floor(box.maxZ); z++) {
                    m.set(x, y, z);
                    BlockState st = w.getBlockState(m);
                    if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                    if (w.getBlockEntity(m) != null) continue; // sandiklari vs. kirma
                    float hardness = st.getHardness(w, m);
                    if (hardness < 0 || hardness > 30) continue; // bedrock / obsidyen kirilmaz
                    if (st.getCollisionShape(w, m).isEmpty()) continue;
                    w.breakBlock(m, true, p);
                    broke = true;
                }
            }
        }
        if (broke) {
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.PLAYERS, 0.8f, 0.8f);
            w.spawnParticles(ParticleTypes.EXPLOSION, p.getX() + dir.x, p.getY() + 1, p.getZ() + dir.z, 1, 0, 0, 0, 0);
        }
    }

    private static void scareMobs(ServerPlayerEntity p, RageData d, ServerWorld w) {
        int now = w.getServer().getTicks();
        for (PathAwareEntity mob : w.getEntitiesByClass(PathAwareEntity.class, p.getBoundingBox().expand(16),
                e -> e.isAlive() && e != d.grabbed && !isOwnedBy(e, p))) {
            Integer until = BRAVE_UNTIL.get(mob);
            if (until != null && until > now) continue; // bu yaratik su an cesur, saldiriyor
            if (mob.getRandom().nextFloat() < 0.04f) {   // arada bir cesaretlenip saldirsin
                BRAVE_UNTIL.put(mob, now + 60);
                continue;
            }
            if (mob.getTarget() == p) mob.setTarget(null);
            if (mob.getNavigation().isIdle() || mob.squaredDistanceTo(p) < 49) {
                Vec3d away = NoPenaltyTargeting.findFrom(mob, 16, 7, p.getPos());
                if (away != null) mob.getNavigation().startMovingTo(away.x, away.y, away.z, 1.4);
            }
        }
    }

    // ------------------------------------------------------------------ hooks from mixin

    /** @return true ise hasar iptal edilir */
    public static boolean onDamage(LivingEntity self, DamageSource source, float amount) {
        Entity attacker = source.getAttacker();

        if (self instanceof ServerPlayerEntity p) {
            RageData d = get(p);
            if (d.active) {
                if (source.isOf(DamageTypes.FALL)) return true;
                if (attacker != null && !(attacker instanceof PlayerEntity) && p.getRandom().nextFloat() < BLOCK_CHANCE) {
                    ServerWorld w = (ServerWorld) p.getWorld();
                    w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 0.7f);
                    w.spawnParticles(ParticleTypes.ENCHANTED_HIT, p.getX(), p.getY() + 1, p.getZ(), 8, 0.4, 0.5, 0.4, 0.1);
                    return true;
                }
            } else if (amount > 0 && !p.isCreative() && !p.isSpectator() && !p.isInvulnerableTo(source)) {
                boolean wasFull = d.bar >= 100f;
                d.bar = Math.min(100f, d.bar + amount * 4f);
                d.ticksSinceDamaged = 0;
                if (!wasFull && d.bar >= 100f) {
                    p.sendMessage(Text.literal("SİNİR BARI DOLDU! [R] ile patla!").formatted(Formatting.RED, Formatting.BOLD), true);
                    p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.5f, 1f);
                }
            }
        }

        // Rage'deki oyuncu biri(ler)ine vurdu: kan + metre dolar
        if (attacker instanceof ServerPlayerEntity ap && ap != self) {
            RageData ad = get(ap);
            if (ad.active) {
                blood((ServerWorld) self.getWorld(), self.getX(), self.getY() + self.getHeight() / 2, self.getZ(), 20);
                ad.ticksSinceHit = 0;
                ad.meter = Math.min(100f, ad.meter + 2.5f);
            }
        }
        return false;
    }

    public static void onDeath(LivingEntity self, DamageSource source) {
        if (!(self.getWorld() instanceof ServerWorld w)) return;

        // Rage'deki oyuncu oldurduyse: bol kan
        if (isRaging(source.getAttacker())) {
            blood(w, self.getX(), self.getY() + self.getHeight() / 2, self.getZ(), 60);
            w.playSound(null, self.getBlockPos(), SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.HOSTILE, 1.2f, 0.6f);
        }

        // Evcil hayvan oldu: sahibi DELIRIR
        if (self instanceof Tameable t && t.getOwnerUuid() != null && w.getServer() != null) {
            ServerPlayerEntity owner = w.getServer().getPlayerManager().getPlayer(t.getOwnerUuid());
            if (owner != null) {
                RageData d = get(owner);
                d.petRage = true;
                d.bar = 100f;
                if (d.active) {
                    d.meter = 100f;
                    d.freezeTicks = Math.max(d.freezeTicks, FREEZE_PET);
                }
                owner.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 40, 20));
                owner.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("ONU ÖLDÜRDÜLER").formatted(Formatting.DARK_RED, Formatting.BOLD)));
                owner.sendMessage(Text.literal("İntikam zamanı. [R] bas.").formatted(Formatting.RED), true);
                owner.getWorld().playSound(null, owner.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2f, 0.7f);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    public static void blood(ServerWorld w, double x, double y, double z, int count) {
        w.spawnParticles(BLOOD, x, y, z, count, 0.3, 0.4, 0.3, 0.1);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.getDefaultState()),
                x, y, z, count / 2 + 1, 0.3, 0.3, 0.3, 0.15);
    }

    private static boolean isOwnedBy(Entity e, PlayerEntity p) {
        return e instanceof Tameable t && p.getUuid().equals(t.getOwnerUuid());
    }

    private static Entity raycastEntity(ServerPlayerEntity p, double range) {
        Vec3d start = p.getEyePos();
        Vec3d look = p.getRotationVec(1f);
        Vec3d end = start.add(look.multiply(range));
        Box box = p.getBoundingBox().stretch(look.multiply(range)).expand(1.0);
        EntityHitResult hit = ProjectileUtil.raycast(p, start, end, box,
                e -> !e.isSpectator() && e.canHit() && e != p, range * range);
        return hit == null ? null : hit.getEntity();
    }

    private static void addMod(ServerPlayerEntity p, EntityAttribute attr, UUID id, double value, EntityAttributeModifier.Operation op) {
        EntityAttributeInstance inst = p.getAttributeInstance(attr);
        if (inst == null) return;
        if (inst.getModifier(id) != null) inst.removeModifier(id);
        inst.addTemporaryModifier(new EntityAttributeModifier(id, "rage", value, op));
    }

    private static void removeMod(ServerPlayerEntity p, EntityAttribute attr, UUID id) {
        EntityAttributeInstance inst = p.getAttributeInstance(attr);
        if (inst != null && inst.getModifier(id) != null) inst.removeModifier(id);
    }

    private static void removeAllMods(ServerPlayerEntity p) {
        removeMod(p, EntityAttributes.GENERIC_MAX_HEALTH, HEALTH_ID);
        removeMod(p, EntityAttributes.GENERIC_ARMOR, ARMOR_ID);
        removeMod(p, EntityAttributes.GENERIC_ARMOR_TOUGHNESS, TOUGH_ID);
        removeMod(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, KB_ID);
        removeMod(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, SPEED_ID);
    }

    private static void sync(ServerPlayerEntity p, RageData d) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(d.active);
        buf.writeFloat(d.bar);
        buf.writeFloat(d.meter);
        buf.writeVarInt(d.freezeTicks);
        buf.writeVarInt(d.screamCooldown);
        buf.writeBoolean(d.grabbed != null);
        buf.writeBoolean(d.petRage);
        ServerPlayNetworking.send(p, RageNet.SYNC, buf);
    }
}
