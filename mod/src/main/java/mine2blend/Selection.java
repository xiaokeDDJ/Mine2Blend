package mine2blend;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** 游戏内选区：手持选区工具，左键方块 = 点1，右键方块 = 点2；对空气右键 = 远距离选点（潜行时为点1）。 */
public final class Selection {
    public static volatile BlockPos pos1, pos2;
    public static volatile String dimension = "";
    private static long lastAttackMs;
    private static BlockPos lastAttackPos;

    private Selection() {}

    static boolean isWand(PlayerEntity player, Hand hand) {
        ItemStack s = player.getStackInHand(hand);
        if (s.isEmpty()) return false;
        if (s.isOf(Mine2Blend.SELECTION_WAND)) return true;
        String extra = Mine2BlendClient.CONFIG.extraWandItem;
        return extra != null && !extra.isEmpty() && Registries.ITEM.getId(s.getItem()).toString().equals(extra);
    }

    public static synchronized void set(int which, BlockPos pos, World world, boolean announce) {
        String dim = world.getRegistryKey().getValue().toString();
        if (!dim.equals(dimension)) {
            // 换了维度，旧选区作废
            pos1 = null;
            pos2 = null;
            dimension = dim;
        }
        if (which == 1) pos1 = pos.toImmutable();
        else pos2 = pos.toImmutable();
        if (announce) announce(which);
    }

    public static synchronized void setBoth(BlockPos a, BlockPos b, String dim) {
        pos1 = a.toImmutable();
        pos2 = b.toImmutable();
        dimension = dim;
    }

    public static synchronized void clear() {
        pos1 = null;
        pos2 = null;
    }

    static void announce(int which) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        BlockPos p = which == 1 ? pos1 : pos2;
        String msg = String.format("§6[Mine2Blend]§r 点%d: §b%d %d %d", which, p.getX(), p.getY(), p.getZ());
        if (pos1 != null && pos2 != null) {
            int[] s = size();
            msg += String.format("  §7选区 %d×%d×%d = %,d 方块", s[0], s[1], s[2], (long) s[0] * s[1] * s[2]);
        }
        mc.player.sendMessage(Text.literal(msg), false);
    }

    public static int[] size() {
        BlockPos a = pos1, b = pos2;
        if (a == null || b == null) return new int[]{0, 0, 0};
        return new int[]{
                Math.abs(a.getX() - b.getX()) + 1,
                Math.abs(a.getY() - b.getY()) + 1,
                Math.abs(a.getZ() - b.getZ()) + 1};
    }

    static void init() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (!isWand(player, hand)) return ActionResult.PASS;
            if (world.isClient) {
                long now = System.currentTimeMillis();
                // 创造模式按住左键会连续触发，做个去抖
                if (!pos.equals(lastAttackPos) || now - lastAttackMs > 400) {
                    set(1, pos, world, true);
                }
                lastAttackMs = now;
                lastAttackPos = pos.toImmutable();
            }
            return ActionResult.FAIL; // 取消挖掘
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!isWand(player, hand)) return ActionResult.PASS;
            if (world.isClient && hand == Hand.MAIN_HAND) {
                set(player.isSneaking() ? 1 : 2, hit.getBlockPos(), world, true);
            }
            return ActionResult.FAIL; // 取消放置/交互，且不发包给服务器
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (!isWand(player, hand)) return TypedActionResult.pass(stack);
            if (world.isClient && hand == Hand.MAIN_HAND) {
                HitResult hr = player.raycast(512, 0f, false);
                if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
                    set(player.isSneaking() ? 1 : 2, bhr.getBlockPos(), world, true);
                }
            }
            return TypedActionResult.fail(stack);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("m2b")
                        .then(ClientCommandManager.literal("pos1").executes(ctx -> {
                            var p = ctx.getSource().getPlayer();
                            set(1, p.getBlockPos(), p.getWorld(), true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("pos2").executes(ctx -> {
                            var p = ctx.getSource().getPlayer();
                            set(2, p.getBlockPos(), p.getWorld(), true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("clear").executes(ctx -> {
                            clear();
                            ctx.getSource().sendFeedback(Text.literal("[Mine2Blend] 选区已清除").formatted(Formatting.GOLD));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("expand")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(-1024, 1024)).executes(ctx -> {
                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                    BlockPos a = pos1, b = pos2;
                                    if (a == null || b == null) {
                                        ctx.getSource().sendError(Text.literal("[Mine2Blend] 请先选好两个点"));
                                        return 0;
                                    }
                                    BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()) - n, Math.min(a.getY(), b.getY()) - n, Math.min(a.getZ(), b.getZ()) - n);
                                    BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()) + n, Math.max(a.getY(), b.getY()) + n, Math.max(a.getZ(), b.getZ()) + n);
                                    setBoth(lo, hi, dimension);
                                    announce(2);
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("wand").executes(ctx -> giveWand()))
                        .then(ClientCommandManager.literal("info").executes(ctx -> {
                            String wand = "选区棒" + (Mine2BlendClient.CONFIG.extraWandItem.isEmpty() ? "" : " / " + Mine2BlendClient.CONFIG.extraWandItem);
                            ctx.getSource().sendFeedback(Text.literal(String.format(
                                    "§6[Mine2Blend]§r 端口 %d  %s  选区工具: %s\n点1=%s 点2=%s",
                                    Mine2BlendClient.CONFIG.port,
                                    BridgeServer.isRunning() ? "§a接口运行中§r" : "§c接口未运行§r",
                                    wand, fmt(pos1), fmt(pos2))));
                            return 1;
                        }))
        ));

        WorldRenderEvents.LAST.register(ctx -> {
            BlockPos a = pos1, b = pos2;
            if (a == null && b == null) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null || !mc.world.getRegistryKey().getValue().toString().equals(dimension)) return;
            MatrixStack ms = ctx.matrixStack();
            if (ms == null) return;
            Vec3d cam = ctx.camera().getPos();
            VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
            VertexConsumer vc = imm.getBuffer(RenderLayer.getLines());
            ms.push();
            ms.translate(-cam.x, -cam.y, -cam.z);
            if (a != null && b != null) {
                Box box = new Box(
                        Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1)
                        .expand(0.01);
                WorldRenderer.drawBox(ms, vc, box, 1f, 0.75f, 0.1f, 1f);
            }
            if (a != null) WorldRenderer.drawBox(ms, vc, new Box(a).expand(0.02), 1f, 0.2f, 0.2f, 1f);
            if (b != null) WorldRenderer.drawBox(ms, vc, new Box(b).expand(0.02), 0.2f, 0.5f, 1f, 1f);
            ms.pop();
            imm.draw(RenderLayer.getLines());
        });
    }

    /** 单人游戏：在集成服务器上直接给予；多人创造模式：通过创造物品栏放入当前手持格 */
    static int giveWand() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        var srv = mc.getServer();
        if (srv != null) {
            java.util.UUID id = mc.player.getUuid();
            srv.execute(() -> {
                var sp = srv.getPlayerManager().getPlayer(id);
                if (sp != null) sp.giveItemStack(new ItemStack(Mine2Blend.SELECTION_WAND));
            });
        } else if (mc.player.getAbilities().creativeMode && mc.interactionManager != null) {
            int slot = mc.player.getInventory().selectedSlot;
            ItemStack st = new ItemStack(Mine2Blend.SELECTION_WAND);
            mc.player.getInventory().setStack(slot, st);
            mc.interactionManager.clickCreativeStack(st, 36 + slot);
        } else {
            mc.player.sendMessage(Text.literal("§c[Mine2Blend] 多人生存模式下无法直接获取，请在创造物品栏「工具」页找到选区棒"), false);
            return 0;
        }
        mc.player.sendMessage(Text.literal("§6[Mine2Blend]§r 已获得 §b选区棒§r：左键方块=点1，右键方块=点2"), false);
        return 1;
    }

    static String fmt(BlockPos p) {
        return p == null ? "无" : p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
