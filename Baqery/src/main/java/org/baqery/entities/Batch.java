package org.baqery.entities;

import java.util.Set;
import java.util.TreeSet;

import javax.measure.quantity.Quantity;

import org.jscience.physics.amount.Amount;
import org.observe.assoc.ObservableMap;
import org.observe.collect.ObservableCollection;
import org.observe.util.TypeTokens;
import org.qommons.QommonsUtils;
import org.qommons.StringUtils;

public interface Batch extends InventoryItem {
	@Override
	Recipe getIngredient();

	ObservableMap<Long, InventoryItem> getIngredients();

	double getCostOverride();
	Batch setCostOverride(double overrideCost);

	default double getIngredientCost() {
		double cost = 0;
		for (IngredientAmount ing : getIngredient().getIngredients().getValues()) {
			InventoryItem inventory = getIngredients().get(ing.getIngredient());
			cost += inventory.getCost(ing.getAmount());
		}
		return cost;
	}

	default double getBatchCost() {
		return getIngredientCost() + getIngredient().getLaborCost();
	}

	@Override
	default double getCost(Amount<?> amount) throws UnsupportedOperationException {
		if (getIngredient().getStandardBatch() == null)
			throw new UnsupportedOperationException("Standard batch is not configured");
		if (!amount.getUnit().isCompatible(getIngredient().getStandardBatch().getUnit()))
			throw new UnsupportedOperationException(
				"Amount " + amount + " is not compatible with standard batch size " + getIngredient().getStandardBatch());
		double batches = ((Amount<Quantity>) amount).doubleValue(((Amount<Quantity>) getIngredient().getStandardBatch()).getUnit())
			/ getIngredient().getStandardBatch().getEstimatedValue();
		double standardCost = getBatchCost();
		return batches * standardCost;
	}

	@Override
	default ObservableCollection<Allergen> getAllergens() {
		Set<Allergen> allergens = new TreeSet<>((a1, a2) -> StringUtils.compareNumberTolerant(a1.getName(), a2.getName(), true, true));
		for (InventoryItem inv : getIngredients().values())
			allergens.addAll(inv.getAllergens());
		return ObservableCollection.of(TypeTokens.get().of(Allergen.class), QommonsUtils.unmodifiableCopy(allergens));
	}
}
