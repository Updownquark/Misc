package org.baqery;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.measure.quantity.Mass;
import javax.measure.quantity.Quantity;
import javax.measure.quantity.Volume;
import javax.measure.unit.NonSI;
import javax.measure.unit.SI;
import javax.measure.unit.Unit;

import org.jscience.physics.amount.Amount;
import org.qommons.io.Format;

import com.google.common.reflect.TypeToken;

public class BaqeryUtils {
	public static final TypeToken<Amount<?>> AMOUNT_TYPE = new TypeToken<Amount<?>>() {};
	public static final TypeToken<Amount<Volume>> VOLUME_AMOUNT_TYPE = new TypeToken<Amount<Volume>>() {};
	public static final TypeToken<Amount<Mass>> MASS_AMOUNT_TYPE = new TypeToken<Amount<Mass>>() {};

	public static final Format<Amount<Quantity>> AMOUNT_FORMAT = new AmountFormat<Quantity>(//
		"g", SI.GRAM, "lb", NonSI.POUND, "oz", NonSI.OUNCE, //
		"L", NonSI.LITER, "floz", NonSI.OUNCE_LIQUID_US, "tsp", USVolumes.TEA_SPOON, "tbsp", USVolumes.TABLE_SPOON, "cup", USVolumes.CUP, //
		"pt", USVolumes.PINT, "qt", USVolumes.QUART, "gal", NonSI.GALLON_LIQUID_US);
	public static final Format<Amount<Volume>> VOLUME_AMOUNT_FORMAT;
	public static final Format<Amount<Mass>> MASS_AMOUNT_FORMAT;

	public static class AmountFormat<Q extends Quantity> implements Format<Amount<Q>> {
		private final Map<String, Unit<? extends Q>> theUnits;

		public AmountFormat(Object... units) {
			theUnits = new LinkedHashMap<>(units.length);
			for (int i = 0; i < units.length; i += 2)
				theUnits.put((String) units[i], (Unit<? extends Q>) units[i + 1]);
		}
	}
}
