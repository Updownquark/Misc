package org.qommons.misc.springdemo.service;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.qommons.QommonsUtils;
import org.qommons.ThreadConstraint;
import org.qommons.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class ScenarioDataService {
	private final Random theRandom;
	@Value("${demoData.scenarios.dir}")
	private String scenarioDataPath;
	private File theScenarioDataDir;
	private final ConcurrentHashMap<Long, ScenarioDataImpl> theMockScenarios;

	public ScenarioDataService(ResourceLoader resources) {
		theRandom = new Random();
		theMockScenarios = new ConcurrentHashMap<>();
	}

	@PostConstruct
	private void init() {
		theScenarioDataDir = new File(scenarioDataPath);
	}

	public ScenarioData getScenarioData(long scenarioId) {
		return theMockScenarios.computeIfAbsent(scenarioId,
			_ -> new ScenarioDataImpl(scenarioId, "Scenario " + (theMockScenarios.size() + 1)));
	}

	/**
	 * 
	 * @param sourceScenario The scenario to copy
	 * @param newScenarioId The ID of the new scenario
	 * @param name The name for the new scenario
	 */
	public ScenarioData copyScenario(ScenarioData sourceScenario, String name) {
		long newScenarioId = QommonsUtils.randomLong(theRandom);
		ScenarioDataImpl newScenario = new ScenarioDataImpl(newScenarioId, name);
		theMockScenarios.put(newScenarioId, newScenario);
		return newScenario;
	}

	public void deleteScenario(Long scenarioId) {
		theMockScenarios.remove(scenarioId);
	}

	private static class ScenarioDataImpl implements ScenarioData {
		private final long theId;
		private String theName;

		ScenarioDataImpl(long id, String name) {
			theId = id;
			theName = name;
		}

		@Override
		public Transaction lockWrite(boolean tryOnly, Object cause) {
			return Transaction.NONE;
		}

		@Override
		public Transaction lock(boolean tryOnly) {
			return Transaction.NONE;
		}

		@Override
		public CoreId getCoreId() {
			return CoreId.EMPTY;
		}

		@Override
		public ThreadConstraint getThreadConstraint() {
			return ThreadConstraint.ANY;
		}

		@Override
		public long getId() {
			return theId;
		}

		@Override
		public String getName() {
			return theName;
		}
	}
}
