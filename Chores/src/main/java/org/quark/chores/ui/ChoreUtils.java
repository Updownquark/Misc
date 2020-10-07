package org.quark.chores.ui;

import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

import org.qommons.TimeUtils;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;

import com.google.common.reflect.TypeToken;

public class ChoreUtils {
	public static SpinnerFormat<List<String>> LABEL_SET_FORMAT = new SpinnerFormat.ListFormat<>(SpinnerFormat.NUMERICAL_TEXT, ",", " ");
	public static final TypeToken<List<String>> LABEL_SET_TYPE = new TypeToken<List<String>>() {
	};
	public static final Format<Instant> DATE_FORMAT = SpinnerFormat.flexDate(Instant::now, "EEE MMM d", TimeZone.getDefault(),
			TimeUtils.DateElementType.Minute, false);
	public static final int DEFAULT_PREFERENCE = 5;
}
