package org.qommons.misc.springdemo.repository;

import java.util.List;
import java.util.Optional;

import org.qommons.misc.springdemo.entities.ResourceAccessType;
import org.qommons.misc.springdemo.entities.ScenarioSharing;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ScenarioSharingRepository extends CrudRepository<ScenarioSharing, Long> {
	List<ScenarioSharing> getByUserId(@Param("userId") String userId);

	List<ScenarioSharing> getByRole(String role);

	@Query("SELECT access FROM ScenarioSharing WHERE user.id=:userId AND scenario.id=:scenarioId")
	Optional<ResourceAccessType> getAccessByUserAndScenario(@Param("userId") String userId, @Param("scenarioId") long scenarioId);

	@Query("SELECT access FROM ScenarioSharing WHERE role=:roleName AND scenario.id=:scenarioId")
	Optional<ResourceAccessType> getAccessByRoleAndScenario(@Param("roleName") String role, @Param("scenarioId") long scenarioId);

	void deleteByScenarioId(long scenarioId);
}
