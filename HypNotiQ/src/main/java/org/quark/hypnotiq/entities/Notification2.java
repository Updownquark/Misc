package org.quark.hypnotiq.entities;

import java.time.Duration;
import java.time.Instant;

import org.observe.config.ParentReference;
import org.observe.util.NamedEntity;

public interface Notification2 extends NamedEntity {
	@ParentReference
	Note2 getNote();

	Instant getInitialTime();
	Notification2 setInitialTime(Instant time);

	/**
	 * @return The recurrence interval of this Notification. This value is a string representation of the interval like "1d" or "3mo" or
	 *         "1y". There is no standard representation I know of for variable intervals like months and years, so this must be a String.
	 */
	String getRecurInterval();
	Notification2 setRecurInterval(String recur);

	Instant getEndTime();
	Notification2 setEndTime(Instant end);

	int getSnoozeCount();
	Notification2 setSnoozeCount(int snoozeCount);

	Instant getSnoozeTime();
	Notification2 setSnoozeTime(Instant snoozeTime);

	boolean isActive();
	Notification2 setActive(boolean active);

	Instant getLastAlertTime();
	Notification2 setLastAlertTime(Instant lastAlertTime);

	Duration getBeforeTime();
	Notification2 setBeforeTime(Duration beforeTime);
}
