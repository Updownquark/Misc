package org.baqery.entities;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.measure.quantity.Quantity;

import org.jscience.physics.amount.Amount;
import org.observe.config.ObservableValueSet;
import org.qommons.QommonsUtils;
import org.qommons.StringUtils;

public interface Recipe extends Ingredient {
	ObservableValueSet<IngredientAmount> getIngredients();

	Amount<?> getStandardBatch();
	Recipe setStandardBatch(Amount<?> amount);

	default double getStandardBatchCost() {
		double cost = 0;
		for (IngredientAmount ing : getIngredients().getValues())
			cost += ing.getIngredient().getCost(ing.getAmount());
		return cost;
	}

	@Override
	default double getCost(Amount<?> amount) throws UnsupportedOperationException {
		if (getStandardBatch() == null)
			throw new UnsupportedOperationException("Standard batch is not configured");
		if (!amount.getUnit().isCompatible(getStandardBatch().getUnit()))
			throw new UnsupportedOperationException(
				"Amount " + amount + " is not compatible with standard batch size " + getStandardBatch());
		double batches = ((Amount<Quantity>) amount).doubleValue(((Amount<Quantity>) getStandardBatch()).getUnit())
			/ getStandardBatch().getEstimatedValue();
		double standardCost = getStandardBatchCost();
		return batches * standardCost;
	}

	@Override
	default List<Allergen> getAllergens() {
		Set<Allergen> allergens = new TreeSet<>((a1, a2) -> StringUtils.compareNumberTolerant(a1.getName(), a2.getName(), true, true));
		for (IngredientAmount ing : getIngredients().getValues())
			allergens.addAll(ing.getIngredient().getAllergens());
		return QommonsUtils.unmodifiableCopy(allergens);
	}
}
