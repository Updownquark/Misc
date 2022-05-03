package org.quark.searcher;

/** Whether a particular file attribute is required, forbidden, or allowed */
public enum FileAttributeRequirement {
	/** The attribute may or may not be true for a matching file */
	Maybe,
	/** The attribute must be true for a matching file */
	Yes,
	/** The attribute must not be true for a matching file */
	No;

	boolean matches(boolean value) {
		switch (this) {
		case Maybe:
			return true;
		case Yes:
			return value;
		case No:
			return !value;
		}
		return true; // Just to make it compile
	}
}