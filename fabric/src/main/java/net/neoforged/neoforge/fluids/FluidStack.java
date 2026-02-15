package net.neoforged.neoforge.fluids;

import net.minecraft.world.level.material.Fluid;

public class FluidStack {
    public static final FluidStack EMPTY = new FluidStack(null, 0);

    private final Fluid fluid;
    private int amount;

    public FluidStack(Fluid fluid, int amount) {
        this.fluid = fluid;
        this.amount = Math.max(0, amount);
    }

    public Fluid getFluid() {
        return fluid;
    }

    public int getAmount() {
        return amount;
    }

    public boolean isEmpty() {
        return fluid == null || amount <= 0;
    }

    public FluidStack copy() {
        return new FluidStack(fluid, amount);
    }

    public void shrink(int value) {
        amount = Math.max(0, amount - value);
    }

    public void setAmount(int amount) {
        this.amount = Math.max(0, amount);
    }

    public boolean isFluidEqual(FluidStack other) {
        return other != null && this.fluid == other.fluid;
    }
}
