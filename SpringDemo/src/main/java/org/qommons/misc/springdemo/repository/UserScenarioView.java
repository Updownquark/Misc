package org.qommons.misc.springdemo.repository;

import java.util.Collection;

import org.qommons.misc.springdemo.entities.AvailableScenario;
import org.qommons.misc.springdemo.entities.ScenarioSharing;

import lombok.NonNull;

public record UserScenarioView(@NonNull AvailableScenario scenario, Collection<ScenarioSharing> access) {
	public UserScenarioView(AvailableScenario scenario) {
		this(scenario, null);
	}
}
