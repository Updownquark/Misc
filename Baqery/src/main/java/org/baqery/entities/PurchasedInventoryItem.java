package org.baqery.entities;

import javax.measure.quantity.Mass;
import javax.measure.quantity.Volume;

import org.jscience.physics.amount.Amount;

public interface PurchasedInventoryItem extends InventoryItem {
	@Override
	PurchasedIngredient getIngredient();

	double getVolumeCost();
	PurchasedIngredient setVolumeCost(double cost);
	Amount<Volume> getVolumeAmount();
	PurchasedIngredient setVolumeAmount(Amount<Volume> amount);

	double getMassCost();
	PurchasedIngredient setMassCost(double cost);
	Amount<Mass> getMassAmount();
	PurchasedIngredient setMassAmount(Amount<Mass> amount);
}
