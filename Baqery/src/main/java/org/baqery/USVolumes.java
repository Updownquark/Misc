package org.baqery;

import javax.measure.quantity.Volume;
import javax.measure.unit.NonSI;
import javax.measure.unit.Unit;

public class USVolumes {
	public static final Unit<Volume> TABLE_SPOON = NonSI.OUNCE_LIQUID_US.times(2).alternate("tbsp");
	public static final Unit<Volume> TEA_SPOON = TABLE_SPOON.divide(3).alternate("tsp");
	public static final Unit<Volume> CUP = TABLE_SPOON.times(16).alternate("cup");
	public static final Unit<Volume> PINT = CUP.times(2).alternate("pt");
	public static final Unit<Volume> QUART = PINT.times(2).alternate("qt");
}
