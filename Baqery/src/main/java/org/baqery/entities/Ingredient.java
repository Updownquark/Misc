package org.baqery.entities;

import java.util.List;

import org.jscience.physics.amount.Amount;
import org.qommons.Named;

public interface Ingredient extends Named {
	Ingredient setName(String name);

	String getDescription();
	Ingredient setDescription(String descrip);

	String getNotes();
	Ingredient setNotes(String notes);

	double getCost(Amount<?> amount) throws UnsupportedOperationException;

	List<Allergen> getAllergens();
}
