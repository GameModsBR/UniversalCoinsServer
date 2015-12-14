package br.com.gamemods.universalcoinsserver;

import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;

public class RecipeEnderCard implements IRecipe
{

    private ItemStack newStack;
    private Item[] recipeItems = {null, Items.ender_pearl, null, Items.ender_pearl, UniversalCoinsServer.proxy.itemCard,
            Items.ender_pearl, null, Items.ender_pearl, null};

    @Override
    public boolean matches(InventoryCrafting var1, World var2)
    {
        this.newStack = null;
        for (int j = 0; j < var1.getSizeInventory(); j++)
        {
            if (var1.getStackInSlot(j) == null && recipeItems[j] != null)
            {
                return false;
            }
            if (var1.getStackInSlot(j) != null && var1.getStackInSlot(j).getItem() != recipeItems[j])
            {
                return false;
            }
        }
        this.newStack = new ItemStack(UniversalCoinsServer.proxy.itemEnderCard);
        this.newStack.setTagCompound(var1.getStackInSlot(4).getTagCompound());
        return true;
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting var1)
    {
        return newStack;
    }

    @Override
    public int getRecipeSize()
    {
        return 9;
    }

    @Override
    public ItemStack getRecipeOutput()
    {
        return newStack;
    }

}
