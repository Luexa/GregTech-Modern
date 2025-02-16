package com.gregtechceu.gtceu.api.recipe.modifier;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import net.minecraft.MethodsReturnNonnullByDefault;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ParallelLogic {

    /**
     * Calculates the maximum parallel amount that can be done for the given machine and recipe, up to the passed limit
     *
     * @param machine       machine to test against
     * @param recipe        recipe to test with
     * @param parallelLimit hard upper limit of parallels that can be done
     * @return The number of possible parallels, 0 if the recipe cannot be done
     */
    public static int getParallelAmount(MetaMachine machine, GTRecipe recipe, int parallelLimit) {
        if (parallelLimit <= 1) return parallelLimit;
        if (!(machine instanceof IRecipeLogicMachine rlm)) return 1;
        // First check if we are limited by recipe inputs. This can short circuit a lot of consecutive checking
        int maxInputMultiplier = limitByInput(rlm, recipe, parallelLimit);
        if (maxInputMultiplier == 0) return 0;

        // Simulate the merging of the maximum amount of recipes that can be run with these items
        // and limit by the amount we can successfully merge
        return limitByOutputMerging(rlm, recipe, maxInputMultiplier, rlm::canVoidRecipeOutputs);
    }

    /**
     * @param holder        The inventories
     * @param recipe        The recipe
     * @param parallelLimit hard cap on the amount returned
     * @return returns the amount of possible time a recipe can be made from a given input inventory
     */
    public static int limitByInput(IRecipeCapabilityHolder holder, GTRecipe recipe, int parallelLimit) {
        IntSet multipliers = new IntOpenHashSet();

        // non-tick inputs.
        for (RecipeCapability<?> cap : recipe.inputs.keySet()) {
            if (cap.doMatchInRecipe()) {
                // Find the maximum number of recipes that can be performed from the contents of the input inventories
                multipliers.add(cap.getMaxParallelRatio(holder, recipe, parallelLimit));
            }
        }

        // tick inputs.
        for (RecipeCapability<?> cap : recipe.tickInputs.keySet()) {
            if (cap.doMatchInRecipe()) {
                // Find the maximum number of recipes that can be performed from the contents of the input inventories
                multipliers.add(cap.getMaxParallelRatio(holder, recipe, parallelLimit));
            }
        }
        if (multipliers.intStream().allMatch(value -> value == Integer.MAX_VALUE)) {
            return 0;
        }
        // Find the maximum number of recipes that can be performed from all available inputs
        return multipliers.intStream().min().orElse(0);
    }

    /**
     * @param holder        the inventories
     * @param recipe        The recipe
     * @param parallelLimit the maximum expected amount
     * @param canVoid       predicate for what parallel limits should be ignored
     * @return returns the amount of recipes that can be merged successfully into a given output inventory
     */
    public static int limitByOutputMerging(IRecipeCapabilityHolder holder, GTRecipe recipe, int parallelLimit,
                                           Predicate<RecipeCapability<?>> canVoid) {
        Object2IntMap<RecipeCapability<?>> modifiedParallelAmounts = new Object2IntOpenHashMap<>();
        boolean canVoidAll = true;
        for (RecipeCapability<?> cap : recipe.outputs.keySet()) {
            modifiedParallelAmounts.put(cap, Integer.MAX_VALUE);
            if (!canVoid.test(cap)) {
                canVoidAll = false;
            }
        }
        for (RecipeCapability<?> cap : recipe.tickOutputs.keySet()) {
            modifiedParallelAmounts.put(cap, Integer.MAX_VALUE);
            if (!canVoid.test(cap)) {
                canVoidAll = false;
            }
        }
        // If we are voiding everything, return the maximum number of parallels that can be performed from
        // the inputs
        if (canVoidAll) {
            return parallelLimit;
        }

        for (RecipeCapability<?> cap : recipe.outputs.keySet()) {
            if (!cap.doMatchInRecipe()) {
                continue;
            }
            // Check both normal item outputs and chanced item outputs
            if (!recipe.getOutputContents(cap).isEmpty()) {
                boolean voiding = canVoid.test(cap);
                // If we are voiding items, reset the item limit to the maximum number of parallels
                if (voiding) {
                    modifiedParallelAmounts.put(cap, parallelLimit);
                } else {
                    modifiedParallelAmounts.put(cap, cap.limitParallel(recipe, holder, parallelLimit));
                }

                // If we are not voiding, and cannot fit any items, return 0
                if (modifiedParallelAmounts.getInt(cap) == 0 && !voiding) {
                    return 0;
                }
            }
        }
        for (RecipeCapability<?> cap : recipe.tickOutputs.keySet()) {
            if (!cap.doMatchInRecipe()) {
                continue;
            }
            // Check both normal item outputs and chanced item outputs
            if (!recipe.getTickOutputContents(cap).isEmpty()) {
                boolean voiding = canVoid.test(cap);
                // If we are voiding items, reset the item limit to the maximum number of parallels
                if (voiding) {
                    if (modifiedParallelAmounts.containsKey(cap)) {
                        modifiedParallelAmounts.put(cap, Math.min(modifiedParallelAmounts.getInt(cap), parallelLimit));
                    } else {
                        modifiedParallelAmounts.put(cap, parallelLimit);
                    }
                } else {
                    if (modifiedParallelAmounts.containsKey(cap)) {
                        modifiedParallelAmounts.put(cap, Math.min(modifiedParallelAmounts.getInt(cap),
                                cap.limitParallel(recipe, holder, parallelLimit)));
                    } else {
                        modifiedParallelAmounts.put(cap, cap.limitParallel(recipe, holder, parallelLimit));
                    }
                }

                // If we are not voiding, and cannot fit any items, return 0
                if (modifiedParallelAmounts.getInt(cap) == 0 && !voiding) {
                    return 0;
                }
            }
        }

        return modifiedParallelAmounts.values().intStream().min().orElse(0);
    }

    /**
     * Binary-search-like approach to find the maximum amount that can be inserted
     *
     * @param mergedAll     if the merge was successful.
     *                      If true sets {@code minMultiplier} to the as the current multiplier
     *                      then sets {@code multiplier} to the sum of the mean difference between
     *                      {@code multiplier} and {@code maxMultiplier} plus the remainder of the division, if any,
     *                      and itself
     *                      If false, sets {@code maxMultiplier} as the current multiplier, then sets {@code multiplier}
     *                      to half of its value limited it to no less or than the value of {@code minMultiplier}
     * @param minMultiplier the last known multiplier what was fully merged
     * @param multiplier    the current multiplier
     * @param maxMultiplier the last know multiplier that resulted in simulation failure
     * @return an array consisting of the last known multiplier, new multiplier to be attempted and
     *         the last know multiplier that resulted in failure
     */
    public static int[] adjustMultiplier(boolean mergedAll, int minMultiplier, int multiplier, int maxMultiplier) {
        if (mergedAll) {
            minMultiplier = multiplier;
            int remainder = (maxMultiplier - multiplier) % 2;
            multiplier = multiplier + remainder + (maxMultiplier - multiplier) / 2;
        } else {
            maxMultiplier = multiplier;
            multiplier = (multiplier + minMultiplier) / 2;
        }
        if (maxMultiplier - minMultiplier <= 1) {
            multiplier = maxMultiplier = minMultiplier;
        }
        return new int[] { minMultiplier, multiplier, maxMultiplier };
    }

    /**
     * Fast parallel, the parallel amount is always the 2 times the divisor of parallelLimit.
     *
     * @param machine       recipe holder
     * @param recipe        current recipe
     * @param parallelLimit max parallel limited
     * @return Returns the number of parallels that can be done (fast calc)
     */
    public static int getParallelAmountFast(MetaMachine machine, @NotNull GTRecipe recipe, int parallelLimit) {
        if (parallelLimit <= 1) return parallelLimit;
        if (!(machine instanceof IRecipeCapabilityHolder holder)) return 1;

        while (parallelLimit > 0) {
            var copied = recipe.copy(ContentModifier.multiplier(parallelLimit), false);
            if (copied.matchRecipe(holder).isSuccess() && copied.matchTickRecipe(holder).isSuccess()) {
                return parallelLimit;
            }
            parallelLimit /= 2;
        }
        return 1;
    }
}
