package org.baqery;

import java.text.ParseException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.measure.quantity.Mass;
import javax.measure.quantity.Quantity;
import javax.measure.quantity.Volume;
import javax.measure.unit.NonSI;
import javax.measure.unit.SI;
import javax.measure.unit.Unit;

import org.baqery.entities.Labor;
import org.baqery.entities.LaborType;
import org.jscience.physics.amount.Amount;
import org.observe.config.ObservableValueSet;
import org.qommons.BiTuple;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;

import com.google.common.reflect.TypeToken;

public class BaqeryUtils {
	public static final TypeToken<Amount<?>> AMOUNT_TYPE = new TypeToken<Amount<?>>() {};
	public static final TypeToken<Amount<Volume>> VOLUME_AMOUNT_TYPE = new TypeToken<Amount<Volume>>() {};
	public static final TypeToken<Amount<Mass>> MASS_AMOUNT_TYPE = new TypeToken<Amount<Mass>>() {};

	private static final Map<Unit<?>, String> UNIT_NAMES = new HashMap<>();

	public static final String getUnitName(Unit<?> unit) {
		return UNIT_NAMES.get(unit);
	}
	public static final Format<Amount<?>> AMOUNT_FORMAT = (Format<Amount<?>>) (Format<?>) new AmountFormat<>(//
		"g", SI.GRAM, "kg", SI.KILOGRAM, "lb", NonSI.POUND, "oz", NonSI.OUNCE, //
		"L", NonSI.LITER, "mL", SI.MILLI(NonSI.LITER), "floz", NonSI.OUNCE_LIQUID_US, //
		"tsp", USVolumes.TEA_SPOON, "tbsp", USVolumes.TABLE_SPOON, "cup", USVolumes.CUP, //
		"pt", USVolumes.PINT, "qt", USVolumes.QUART, "gal", NonSI.GALLON_LIQUID_US);
	public static final Format<Amount<Volume>> VOLUME_AMOUNT_FORMAT = new AmountFormat<>(//
		"L", NonSI.LITER, "mL", SI.MILLI(NonSI.LITER), "floz", NonSI.OUNCE_LIQUID_US, //
		"tsp", USVolumes.TEA_SPOON, "tbsp", USVolumes.TABLE_SPOON, "cup", USVolumes.CUP, //
		"pt", USVolumes.PINT, "qt", USVolumes.QUART, "gal", NonSI.GALLON_LIQUID_US);
	public static final Format<Amount<Mass>> MASS_AMOUNT_FORMAT = new AmountFormat<>(//
		"g", SI.GRAM, "kg", SI.KILOGRAM, "lb", NonSI.POUND, "oz", NonSI.OUNCE);

	public static SpinnerFormat<Labor> laborFormat(Supplier<ObservableValueSet<? extends Labor>> labor, List<? extends LaborType> types) {
		return new LaborFormat(labor, types);
	}

	public static String formatLabor(Labor labor) {
		return formatLabor(labor, new StringBuilder()).toString();
	}

	private static final SpinnerFormat<Duration> TIME_FORMAT = SpinnerFormat.flexDuration();

	private static StringBuilder formatLabor(Labor labor, StringBuilder text) {
		TIME_FORMAT.append(text, labor.getTime());
		text.append(' ');
		text.append(labor.getType().getName());
		return text;
	}

	public static class AmountFormat<Q extends Quantity> implements Format<Amount<Q>> {
		private static final Format<Double> DOUBLE_FORMAT = Format.doubleFormat(3).printIntFor(4, true).withExpCondition(4, 2).build();
		private final Map<String, Unit<? extends Q>> theUnits;

		public AmountFormat(Object... units) {
			theUnits = new LinkedHashMap<>(units.length);
			for (int i = 0; i < units.length; i += 2) {
				theUnits.put(((String) units[i]).toLowerCase(), (Unit<? extends Q>) units[i + 1]);
				UNIT_NAMES.put((Unit<? extends Q>) units[i + 1], (String) units[i]);
			}
		}

		@Override
		public void append(StringBuilder text, Amount<Q> value) {
			DOUBLE_FORMAT.append(text, value.getEstimatedValue());
			String unitName = getUnitName(value.getUnit());
			if (unitName == null)
				throw new IllegalArgumentException("Unrecognized unit: " + value.getUnit());
			text.append(unitName);
		}

		@Override
		public Amount<Q> parse(CharSequence text) throws ParseException {
			Format.ParsedUnitValue<Double> uv = Format.parseUnitValue(text, DOUBLE_FORMAT);
			Unit<? extends Q> unit = theUnits.get(uv.unit.toLowerCase());
			if (unit == null)
				throw new ParseException("Unrecognized unit: " + uv.unit, uv.unitStart);
			return (Amount<Q>) Amount.valueOf(uv.value, unit);
		}
	}

	public static class LaborFormat implements SpinnerFormat<Labor> {
		private final Supplier<ObservableValueSet<? extends Labor>> theLabor;
		private final List<? extends LaborType> theTypes;

		public LaborFormat(Supplier<ObservableValueSet<? extends Labor>> labor, List<? extends LaborType> types) {
			theLabor = labor;
			theTypes = types;
		}

		@Override
		public void append(StringBuilder text, Labor value) {
			formatLabor(value, text);
		}

		@Override
		public Labor parse(CharSequence text) throws ParseException {
			ParsedUnitValue<Duration> uv = Format.parseUnitValue(text, TIME_FORMAT);
			LaborType type = null;
			for (LaborType t : theTypes) {
				if (t.getName().toLowerCase().equals(uv.unit)) {
					type = t;
					break;
				}
			}
			if (type == null)
				throw new ParseException("Unrecognized labor type: " + uv.unit, uv.unitStart);
			return theLabor.get().create()//
				.with(Labor::getTime, uv.value)//
				.with(Labor::getType, type)//
				.create().get();
		}

		@Override
		public boolean supportsAdjustment(boolean withContext) {
			return withContext;
		}

		@Override
		public BiTuple<Labor, String> adjust(Labor value, String formatted, int cursor, boolean up) {
			ParsedUnitValue<Duration> uv;
			try {
				uv = Format.parseUnitValue(formatted, TIME_FORMAT);
			} catch (ParseException e) {
				return null;
			}
			int laborIndex = -1;
			for (int t = 0; t < theTypes.size(); t++) {
				if (theTypes.get(t).getName().toLowerCase().equals(uv.unit)) {
					laborIndex = t;
					break;
				}
			}
			if (laborIndex < 0)
				return null;
			String newFormatted;
			if (cursor >= uv.unitStart) {
				// Increment the labor type
				LaborType type = theTypes.get((laborIndex + 1) % theTypes.size());
				newFormatted = formatted.substring(0, uv.unitStart) + type.getName();
				value.setType(type);
			} else {
				String formattedTime = formatted.substring(0, uv.unitStart);
				while (formattedTime.length() > 0 && Character.isWhitespace(formattedTime.charAt(formattedTime.length() - 1)))
					formattedTime = formattedTime.substring(0, formattedTime.length() - 1);
				if (cursor > formattedTime.length())
					return null;
				BiTuple<Duration, String> adjustedTime = TIME_FORMAT.adjust(uv.value, formattedTime, cursor, up);
				if (adjustedTime == null)
					return null;
				value.setTime(adjustedTime.getValue1());
				newFormatted = adjustedTime.getValue2() + " " + theTypes.get(laborIndex).getName();
			}
			return new BiTuple<>(value, newFormatted);
		}
	}
}
