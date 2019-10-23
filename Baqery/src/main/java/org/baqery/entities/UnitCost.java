package org.baqery.entities;

import org.jscience.physics.amount.Amount;

public interface UnitCost {
	double getCost();
	Amount<?> getUnit();
}
