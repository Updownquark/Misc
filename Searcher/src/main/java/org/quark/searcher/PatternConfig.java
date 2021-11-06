package org.quark.searcher;

public interface PatternConfig {
	String getPattern();

	PatternConfig setPattern(String pattern);

	boolean isCaseSensitive();

	PatternConfig setCaseSensitive(boolean caseSensitive);
}