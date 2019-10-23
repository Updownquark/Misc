package org.baqery.entities;

import org.jscience.physics.amount.Amount;

public interface IngredientAmount {
	Ingredient getIngredient();
	IngredientAmount setIngredient(Ingredient ingredient);

	Amount<?> getAmount();
	IngredientAmount setAmount(Amount<?> amount) throws UnsupportedOperationException;
}
