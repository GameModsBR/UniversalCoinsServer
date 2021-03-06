package br.com.gamemods.universalcoinsserver.blocks;

import br.com.gamemods.universalcoinsserver.GuiHandler;
import br.com.gamemods.universalcoinsserver.UniversalCoinsServer;
import br.com.gamemods.universalcoinsserver.api.UniversalCoinsServerAPI;
import br.com.gamemods.universalcoinsserver.tile.TileSlots;
import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import java.util.List;

public class BlockSlots extends BlockRotary
{
    public BlockSlots(CreativeTabs creativeTabs)
    {
        super(new Material(MapColor.stoneColor));
        setHardness(3.0F);
        setCreativeTab(creativeTabs);
        setResistance(30.0F);
        setBlockName("blockBandit");
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int metadata)
    {
        TileEntity tileEntity = world.getTileEntity(x,y,z);
        if(tileEntity instanceof TileSlots)
        {
            TileSlots tile = (TileSlots) tileEntity;
            List<ItemStack> drops = UniversalCoinsServerAPI.createStacks(tile.userCoins);
            int size = tile.getSizeInventory();
            for(int i = 0; i < size; i++)
            {
                ItemStack stack = tile.getStackInSlot(i);
                if(stack != null)
                    drops.add(stack);
            }

            drop(world, x,y,z, drops);
        }

        super.breakBlock(world, x, y, z, block, metadata);
    }

    @Override
    public TileSlots createNewTileEntity(World p_149915_1_, int p_149915_2_)
    {
        return new TileSlots();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int metadata, float sideX, float sideY, float sideZ)
    {
        TileSlots tile;
        {
            TileEntity te = world.getTileEntity(x,y,z);
            if(te instanceof TileSlots)
                tile = (TileSlots) te;
            else
                return false;
        }

        ItemStack heldItem = player.getHeldItem();
        int open;
        if(heldItem != null && heldItem.getItem() == UniversalCoinsServer.proxy.itemVendorWrench)
            open = GuiHandler.GUI_BANDIT_WRENCH;
        else
            open = GuiHandler.GUI_BANDIT;

        if(tile.isInUse(player))
        {
            player.addChatMessage(new ChatComponentTranslation("chat.warning.inuse"));
            return false;
        }

        player.openGui(UniversalCoinsServer.instance, open, world, x, y, z);
        if(!(player.openContainer instanceof ContainerPlayer))
        {
            tile.setOpener(player);
            tile.scheduleUpdate();
        }

        return true;
    }
}
