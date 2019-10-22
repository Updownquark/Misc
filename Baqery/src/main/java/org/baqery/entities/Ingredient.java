package org.baqery.entities;

import javax.measure.quantity.Mass;
import javax.measure.quantity.Volume;

import org.jscience.economics.money.Money;
import org.jscience.physics.amount.Amount;
import org.observe.collect.ObservableCollection;
import org.qommons.Named;

public interface Ingredient extends Named {
	Ingredient setName(String name);

	Amount<Money> getUnitMassCost();
	Amount<Mass> getUnitMass();
	Ingredient setUnitMassCost(Amount<Money> cost);
	Ingredient setUnitMass(Amount<Mass> unitMass);

	Amount<Money> getUnitVolumeCost();
	Amount<Volume> getUnitVolume();
	Ingredient setUnitVolumeCost(Amount<Money> cost);
	Ingredient setUnitVolume(Amount<Volume> unitVolume);

	ObservableCollection<Allergen> getMaterials();
}
