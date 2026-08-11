package org.qommons.misc.springdemo.service;

public class ScenarioDataMissingException extends RuntimeException {
	private final String theScenarioId;

	public ScenarioDataMissingException(String scenarioId, String message) {
		super(message);
		theScenarioId = scenarioId;
	}

	public String getScenarioId() {
		return theScenarioId;
	}
}
