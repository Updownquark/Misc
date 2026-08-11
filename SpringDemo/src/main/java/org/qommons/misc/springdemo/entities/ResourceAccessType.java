package org.qommons.misc.springdemo.entities;

public enum ResourceAccessType {
	View, Edit, Delete;

	public static final ResourceAccessType MAX = values()[values().length - 1];
}
