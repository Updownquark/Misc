package org.baqery.entities;

public interface PurchasedIngredient extends Ingredient {
	@Override
	PurchasedIngredient setName(String name);

	@Override
	PurchasedIngredient setDescription(String descrip);

	@Override
	PurchasedIngredient setNotes(String notes);
}
