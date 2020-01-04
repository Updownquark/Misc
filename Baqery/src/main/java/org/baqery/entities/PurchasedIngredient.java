package org.baqery.entities;

import javax.measure.quantity.Mass;
import javax.measure.quantity.Volume;

import org.jscience.physics.amount.Amount;

public interface BasicIngredient extends Ingredient {
	@Override
	BasicIngredient setName(String name);

	@Override
	BasicIngredient setDescription(String descrip);

	@Override
	BasicIngredient setNotes(String notes);

	double getVolumeCost();
	BasicIngredient setVolumeCost(double cost);
	Amount<Volume> getVolumeAmount();
	BasicIngredient setVolumeAmount(Amount<Volume> amount);

	double getMassCost();
	BasicIngredient setMassCost(double cost);
	Amount<Mass> getMassAmount();
	BasicIngredient setMassAmount(Amount<Mass> amount);
}
