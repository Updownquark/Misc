package org.baqery.entities;

import java.time.Instant;

import org.jscience.physics.amount.Amount;
import org.observe.collect.ObservableCollection;
import org.observe.util.Identified;
import org.observe.util.NamedEntity;

public interface InventoryItem extends NamedEntity, Identified {
	boolean isActive();
	InventoryItem setActive(boolean active);

	Ingredient getIngredient();

	String getNotes();
	InventoryItem setNotes(String notes);

	Instant getAcquireDate();
	InventoryItem setAcquireDate(Instant acquired);

	double getCost(Amount<?> amount) throws UnsupportedOperationException;

	ObservableCollection<Allergen> getAllergens();
}
