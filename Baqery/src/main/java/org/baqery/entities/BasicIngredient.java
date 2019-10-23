package org.baqery.entities;

import org.observe.config.ObservableValueSet;

public interface BasicIngredient extends Ingredient {
	ObservableValueSet<UnitCost> getUnitCosts();
}
