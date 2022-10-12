package org.quark.finance.logic;

import java.text.DecimalFormat;

public class Money implements Comparable<Money> {
	public static final DecimalFormat FORMAT = new DecimalFormat("#,##0.00");

	public final long value;

	public Money(long value) {
		this.value = value;
	}

	@Override
	public int compareTo(Money o) {
		return Long.compare(value, o.value);
	}

	@Override
	public int hashCode() {
		return Long.hashCode(value);
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof Money && value == ((Money) obj).value;
	}

	@Override
	public String toString() {
		return "$" + FORMAT.format(value / 100.0);
	}
}
