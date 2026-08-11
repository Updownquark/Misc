package org.qommons.misc.springdemo.service;

import org.qommons.Named;
import org.qommons.Transactable;

public interface ScenarioData extends Named, Transactable {
	long getId();

	@Override
	String getName();
}
