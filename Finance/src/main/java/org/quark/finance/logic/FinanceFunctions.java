package org.quark.finance.logic;

public class FinanceFunctions {
	public static Money MAX(Money first, Money... monies) {
		long max = first.value;
		for (Money money : monies) {
			if (money.value > max) {
				max = money.value;
			}
		}
		return new Money(max);
	}

	public static Money MIN(Money first, Money... monies) {
		long min = first.value;
		for (Money money : monies) {
			if (money.value < min) {
				min = money.value;
			}
		}
		return new Money(min);
	}

	public static <C extends Comparable<C>> C MAX(C first, C... others) {
		C max = first;
		for (C inst : others) {
			if (inst.compareTo(max) > 0) {
				max = inst;
			}
		}
		return max;
	}

	public static <C extends Comparable<C>> C MIN(C first, C... others) {
		C min = first;
		for (C inst : others) {
			if (inst.compareTo(min) < 0) {
				min = inst;
			}
		}
		return min;
	}
}
