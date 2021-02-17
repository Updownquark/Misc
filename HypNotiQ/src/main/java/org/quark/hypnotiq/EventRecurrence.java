package org.quark.hypnotiq;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;

import org.qommons.TimeUtils;
import org.qommons.TimeUtils.DurationComponent;
import org.qommons.TimeUtils.ParsedDuration;

/**
 * Represents a recurrence interval for an event. This class supports much more than simple constant-length durations, including:
 * <ul>
 * <li>a particular day of every month</li>
 * <li>a particular day of the month every year</li>
 * <li>the last day or a particular number of days before the end of the month</li>
 * <li>the Xth [weekday] of the month</li>
 * </ul>
 */
public class EventRecurrence {
	private final String theStored;
	private final ParsedDuration theDuration;
	private final int theMonths;
	private final int theNumber;
	private final int theDay;

	private EventRecurrence(String stored, ParsedDuration duration, int months, int number, int day) {
		theStored = stored;
		theDuration = duration;
		theMonths = months;
		theNumber = number;
		theDay = day;
	}

	/**
	 * @param recur
	 *            The String to parse the recurrence from
	 * @param reference
	 *            The reference time of the event
	 * @return A recurrence to use for a recurring event
	 */
	public static EventRecurrence of(String recur, Instant reference) throws ParseException {
		if (reference == null || recur == null || recur.isEmpty()) {
			return null;
		}

		char lastChar = recur.charAt(recur.length() - 1);
		String dStr;
		int day, number;
		switch (lastChar) {
		case '-': // Code for days from the last of the month
			dStr = recur.substring(0, recur.length() - 1);
			Calendar cal = TimeUtils.CALENDAR.get();
			cal.setTimeZone(TimeZone.getDefault());
			cal.setTimeInMillis(reference.toEpochMilli());
			day = cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH);
			number = -1;
			break;
		case '#': // Code for Xth [weekday] of the month
			dStr = recur.substring(0, recur.length() - 1);
			cal = TimeUtils.CALENDAR.get();
			cal.setTimeZone(TimeZone.getDefault());
			cal.setTimeInMillis(reference.toEpochMilli());
			day = cal.get(Calendar.DAY_OF_WEEK);
			number = cal.get(Calendar.WEEK_OF_MONTH);
			break;
		default: // Normal frequency
			day = number = -1;
			dStr = recur;
		}
		ParsedDuration duration;
		if (!dStr.isEmpty()) {
			duration = TimeUtils.parseDuration(dStr);
		} else {
			duration = null;
		}
		int months = 0;
		if (day >= 0) {
			if (duration != null) {
				for (DurationComponent component : duration.getComponents()) {
					switch (component.getField()) {
					case Year:
						months += component.getValue() * 12;
						break;
					case Month:
						months += component.getValue();
						break;
					default:
						throw new ParseException("Bad duration--" + lastChar + " notation can only be used with months and years",
								recur.length() - 1);
					}
				}
			}
			if (months == 0) {
				months = 1;
			}
			duration = null;
		}
		return new EventRecurrence(recur, duration, months, number, day);
	}

	public String getStored() {
		return theStored;
	}

	public ParsedDuration getDuration() {
		return theDuration;
	}

	/**
	 * @param reference
	 *            The reference time for the event
	 * @param relative
	 *            Any arbitrary instant
	 * @param strict
	 *            Whether to return a time strictly after <code>after</code>. If false, a time equal to <code>after</code> may be returned.
	 * @return The occurrence of the event that occurs soonest after (or on, if not <code>strict</code>) <code>after</code>
	 */
	public Instant getOccurrence(Instant reference, Instant relative, boolean after, boolean strict) {
		Instant occur;
		// First, get an estimate that can't be later than after
		if (relative == null) {
			return reference;
		} else if (theDay >= 0) {
			Calendar cal = TimeUtils.CALENDAR.get();
			cal.setTimeZone(TimeZone.getDefault());
			cal.setTimeInMillis(reference.toEpochMilli());
			cal.set(Calendar.DAY_OF_MONTH, 1);
			long afterMillis = relative.toEpochMilli();
			long intervalMillis = theMonths * 31L * 24 * 60 * 60 * 1000;
			int div = (int) ((afterMillis - cal.getTimeInMillis()) / intervalMillis);
			while (div != 0) {
				cal.add(Calendar.MONTH, div * theMonths);
				div = (int) ((afterMillis - cal.getTimeInMillis()) / intervalMillis);
			}
			if (theNumber >= 0) {
				int day = cal.get(Calendar.DAY_OF_WEEK);
				if (day < theDay) {
					cal.add(Calendar.DAY_OF_MONTH, theDay - day);
				} else if (day > theDay) {
					cal.add(Calendar.DAY_OF_MONTH, theDay - day + 7);
				}
				cal.add(Calendar.DAY_OF_MONTH, (theNumber - 1) * 7);
			} else {
				cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH) - theDay);
			}
			occur = Instant.ofEpochMilli(cal.getTimeInMillis());
		} else {
			Duration estDuration = theDuration.asDuration();
			int times = TimeUtils.divide(Duration.between(reference, relative), estDuration);
			if (times > 1) {
				occur = theDuration.times(times - 2).addTo(reference, TimeZone.getDefault());
			} else if (times < 0) {
				occur = theDuration.times(times - 1).addTo(reference, TimeZone.getDefault());
			} else {
				occur = reference;
			}
		}

		// Next, enforce the time constraint
		while (true) {
			int comp = occur.compareTo(relative);
			if (comp == 0) {
				if (!strict) {
					break;
				}
			} else if ((comp > 0) == after) {
				break;
			}
			occur = adjacentOccurrence(occur, after);
		}
		return occur;
	}

	/**
	 * @param occurrence
	 *            An occurrence of the event
	 * @return The occurrence of the event occurring immediately after <code>lastOccurrence</code>
	 */
	public Instant adjacentOccurrence(Instant occurrence, boolean next) {
		if (theNumber >= 0) { // Xth [weekday] of the month
			long millis = occurrence.toEpochMilli();
			Calendar cal = TimeUtils.CALENDAR.get();
			cal.setTimeInMillis(millis);
			cal.set(Calendar.DAY_OF_MONTH, 1);
			cal.add(Calendar.MONTH, theMonths * (next ? 1 : -1));
			int day = cal.get(Calendar.DAY_OF_WEEK);
			if (day < theDay) {
				cal.add(Calendar.DAY_OF_MONTH, theDay - day);
			} else if (day > theDay) {
				cal.add(Calendar.DAY_OF_MONTH, theDay - day + 7);
			}
			cal.add(Calendar.DAY_OF_MONTH, (theNumber - 1) * 7);
			return Instant.ofEpochMilli(cal.getTimeInMillis());
		} else if (theDay >= 0) {// X days before the end of the month
			Calendar cal = TimeUtils.CALENDAR.get();
			cal.setTimeInMillis(occurrence.toEpochMilli());
			cal.set(Calendar.DAY_OF_MONTH, 1);
			cal.add(Calendar.MONTH, theMonths * (next ? 1 : -1));
			cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH) - theDay);
			return Instant.ofEpochMilli(cal.getTimeInMillis());
		} else {
			if (next) {
				return theDuration.addTo(occurrence, TimeZone.getDefault());
			} else {
				return theDuration.times(-1).addTo(occurrence, TimeZone.getDefault());
			}
		}
	}

	@Override
	public String toString() {
		if (theMonths > 0) {
			StringBuilder str = new StringBuilder();
			switch (theMonths) {
			case 1:
				break;
			case 2:
				str.append("every other ");
				break;
			case 3:
				str.append("quarterly, ");
				break;
			case 6:
				str.append("bianually, ");
				break;
			case 12:
				str.append("annually, ");
				break;
			default:
				if (theMonths % 12 == 0) {
					str.append(" every ").append(theMonths / 12).append(" yrs, ");
				} else {
					str.append(" every ").append(theMonths).append(" mos, ");
				}
				break;
			}
			if (theNumber >= 0) {
				str.append(theNumber);
				switch (theNumber) {
				case 1:
					str.append("st");
					break;
				case 2:
					str.append("nd");
					break;
				case 3:
					str.append("rd");
					break;
				default:
					str.append("th");
					break;
				}
				str.append(' ').append(TimeUtils.getWeekDaysAbbrev().get(theDay));
			} else {
				if (theDay == 0) {
					str.append(" last day of month");
				} else {
					str.append(theDay).append(" days before end of month");
				}
			}
			return str.toString();
		} else {
			return theDuration.toString();
		}
	}
}