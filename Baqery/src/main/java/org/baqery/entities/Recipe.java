package org.baqery.entities;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.measure.quantity.Quantity;

import org.jscience.physics.amount.Amount;
import org.observe.config.ObservableValueSet;
import org.qommons.QommonsUtils;
import org.qommons.StringUtils;
import org.qommons.TimeUtils;

public interface Recipe extends Ingredient {
	@Override
	Recipe setName(String name);
	@Override
	Recipe setDescription(String descrip);
	@Override
	Recipe setNotes(String notes);

	ObservableValueSet<IngredientAmount> getIngredients();
	ObservableValueSet<Labor> getLabor();

	Amount<?> getStandardBatch();
	Recipe setStandardBatch(Amount<?> amount);

	String getProcedure();
	Recipe setProcedure(String procedure);

	default double getIngredientCost() {
		double cost = 0;
		for (IngredientAmount ing : getIngredients().getValues())
			cost += ing.getIngredient().getCost(ing.getAmount());
		return cost;
	}

	default Duration getLaborTime() {
		Duration d = Duration.ZERO;
		for (Labor labor : getLabor().getValues())
			d = d.plus(labor.getTime());
		return d;
	}

	default double getLaborCost() {
		double cost = 0;
		for (Labor labor : getLabor().getValues()) {
			double hours = TimeUtils.toSeconds(labor.getTime()) / 60 / 60;
			cost += hours * labor.getType().getHourlyRate();
		}
		return cost;
	}

	default double getBatchCost() {
		return getIngredientCost() + getLaborCost();
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
		double standardCost = getBatchCost();
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
