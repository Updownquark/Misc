package org.baqery.entities;

import java.util.List;

import org.jscience.physics.amount.Amount;
import org.qommons.Named;

public interface Ingredient extends Named {
	Ingredient setName(String name);

	double getCost(Amount<?> amount) throws UnsupportedOperationException;

	List<Allergen> getAllergens();
}
