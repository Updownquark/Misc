package org.baqery.entities;

import org.qommons.Named;

public interface LaborType extends Named {
	LaborType setName(String name);

	double getHourlyRate();
	LaborType setHourlyRate(double rate);
}
