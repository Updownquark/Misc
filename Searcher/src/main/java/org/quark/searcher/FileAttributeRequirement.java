package org.quark.searcher;

public enum FileAttributeRequirement {
	Maybe, Yes, No;

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