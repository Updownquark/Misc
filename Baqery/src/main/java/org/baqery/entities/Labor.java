package org.baqery.entities;

import java.time.Duration;

public interface Labor {
	LaborType getType();
	Labor setType(LaborType type);

	Duration getTime();
	Labor setTime(Duration time);
}
