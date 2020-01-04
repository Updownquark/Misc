package org.baqery.entities;

import java.time.Instant;
import java.util.List;

import org.jscience.physics.amount.Amount;
import org.observe.util.NamedEntity;

public interface InventoryItem extends NamedEntity {
	long getId();

	boolean isActive();
	InventoryItem setActive(boolean active);

	Ingredient getIngredient();

	String getNotes();
	InventoryItem setNotes(String notes);

	Instant getAcquireDate();
	InventoryItem setAcquireDate(Instant acquired);

	double getCost(Amount<?> amount) throws UnsupportedOperationException;

	List<Allergen> getAllergens();
}
