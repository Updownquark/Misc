package org.baqery.entities;

import org.jscience.physics.amount.Amount;

public interface OrderItem {
	Recipe getRecipe();
	OrderItem setRecipe(Recipe recipe);

	Batch getBatch();
	OrderItem setBatch(Batch batch);

	Amount<?> getAmount();
	OrderItem setAmount(Amount<?> amount);
}
