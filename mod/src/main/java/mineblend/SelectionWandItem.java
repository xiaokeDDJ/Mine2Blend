package mineblend;

import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 选区棒：左键 = 点1，右键 = 点2，可远距离选点（具体逻辑在客户端 Selection 中）。不能挖掘方块。 */
public class SelectionWandItem extends Item {
    public SelectionWandItem(Settings settings) {
        super(settings);
    }

    @Override
    public boolean canMine(BlockState state, World world, BlockPos pos, PlayerEntity miner) {
        return false;
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("左键：点1   右键：点2").formatted(Formatting.GRAY));
        tooltip.add(Text.literal("对准远处方块也可以（最远 512 格）").formatted(Formatting.GRAY));
        tooltip.add(Text.literal("Blender「读取游戏内选区」后即可导入").formatted(Formatting.DARK_AQUA));
    }
}
