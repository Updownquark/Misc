package org.quark.finance.ui;

import java.text.ParseException;
import java.util.function.Supplier;

import org.observe.expresso.ExpressoParser;
import org.observe.expresso.ObservableExpression;
import org.qommons.TimeUtils;
import org.qommons.io.Format;
import org.quark.finance.entities.Plan;
import org.quark.finance.entities.Process;
import org.quark.finance.logic.Money;

public class ExpressionFormat implements Format<ObservableExpression> {
	private final ExpressoParser theParser;
	private final Finance theApp;
	private final Supplier<Plan> thePlan;
	private final Supplier<Process> theProcess;
	private final boolean isPersisting;
	private boolean withMoney;
	private boolean withTime;
	private boolean withDuration;
	
	public ExpressionFormat(ExpressoParser parser, Finance app, Supplier<Plan> plan, Supplier<Process> process, boolean persisting) {
		theParser = parser;
		theApp=app;
		thePlan = plan;
		theProcess = process;
		isPersisting = persisting;
		withMoney = withTime = withDuration = true;
	}

	public ExpressionFormat withMoney(boolean money) {
		withMoney = money;
		return this;
	}

	public ExpressionFormat withTime(boolean time) {
		withTime = time;
		return this;
	}

	public ExpressionFormat withDuration(boolean duration) {
		withDuration = duration;
		return this;
	}

	@Override
	public void append(StringBuilder text, ObservableExpression value) {
		if(value!=null) {
			text.append(value);
		}
	}

	@Override
	public ObservableExpression parse(CharSequence text) throws ParseException {
		if (text == null || text.length() == 0) {
			return null;
		}
		if (withMoney) {
			if (text.charAt(0) == '$') {
				try {
					return new ObservableExpression.LiteralExpression<>(null,
						new Money(Math.round(Double.parseDouble(text.subSequence(1, text.length()).toString()) * 100)));
				} catch (NumberFormatException e) {
					throw new ParseException("Could not parse simple monetary value."
						+ " To specify an expression, enclose the amount in grave accents, like `$5.00`.", 1);
				}
			} else if (text.length() > 1 && text.charAt(0) == '-' && text.charAt(1) == '$') {
				try {
					return new ObservableExpression.LiteralExpression<>(null,
						new Money(-Math.round(Double.parseDouble(text.subSequence(2, text.length()).toString()) * 100)));
				} catch (NumberFormatException e) {
					throw new ParseException("Could not parse simple monetary value."
						+ " To specify an expression, enclose the amount in grave accents, like `$-5.00` or -`$5.00`.", 2);
				}
			}
		}
		ParseException ex;
		try {
			ObservableExpression exp = theParser.parse(text.toString());
			if (thePlan != null) {
				Plan plan = thePlan.get();
				Process process = theProcess == null ? null : theProcess.get();
				exp.replaceAll(exp2 -> theApp.replacePlanComponents(exp2, plan, process, isPersisting));
			}
			return exp;
		} catch (ParseException e) {
			ex = e;
		}
		if (withTime) {
			TimeUtils.ParsedInstant time = TimeUtils.parseInstant(text, true, false, null);
			if (time != null) {
				return new ObservableExpression.LiteralExpression<>(null, time);
			}
		}
		if (withDuration) {
			TimeUtils.ParsedDuration duration = TimeUtils.parseDuration(text, true, false);
			if (duration != null) {
				return new ObservableExpression.LiteralExpression<>(null, duration);
			}
		}
		throw ex;
	}
}
