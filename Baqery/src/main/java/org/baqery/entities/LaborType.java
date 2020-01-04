package org.baqery.entities;

import org.observe.util.NamedEntity;

public interface LaborType extends NamedEntity {
	double getHourlyRate();
	LaborType setHourlyRate(double rate);
}
