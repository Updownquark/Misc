package org.quark.searcher;

/** Entity for an excluded file pattern */
public interface PatternConfig {
	/** @return The file name pattern to match */
	String getPattern();

	/**
	 * @param pattern The file name pattern to match
	 * @return This entity
	 */
	PatternConfig setPattern(String pattern);

	/** @return Whether the file name pattern is evaluated case-sensitively */
	boolean isCaseSensitive();

	/**
	 * @param caseSensitive Whether the file name pattern should be evaluated case-sensitively
	 * @return This entity
	 */
	PatternConfig setCaseSensitive(boolean caseSensitive);
}