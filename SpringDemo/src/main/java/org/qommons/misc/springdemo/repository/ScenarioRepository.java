package org.qommons.misc.springdemo.repository;

import java.util.List;

import org.qommons.misc.springdemo.entities.AvailableScenario;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ScenarioRepository extends CrudRepository<AvailableScenario, Long> {
	List<AvailableScenario> getScenariosByOwnerId(String ownerId);

	@Query("SELECT COUNT(s) FROM AvailableScenario s WHERE s.id = :scenarioId AND s.owner.id = :userId")
	int isScenarioOwnedBy(@Param("scenarioId") long scenarioId, @Param("userId") String userId);
}
