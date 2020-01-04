package org.baqery.entities;

import java.time.Duration;

import org.jscience.physics.amount.Amount;
import org.observe.config.ObservableValueSet;
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
}
