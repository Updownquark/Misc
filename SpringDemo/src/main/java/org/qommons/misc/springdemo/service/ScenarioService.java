package org.qommons.misc.springdemo.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.qommons.Transaction;
import org.qommons.misc.springdemo.entities.AvailableScenario;
import org.qommons.misc.springdemo.entities.ResourceAccessType;
import org.qommons.misc.springdemo.entities.ScenarioSharing;
import org.qommons.misc.springdemo.entities.DemoUser;
import org.qommons.misc.springdemo.repository.ResourceAccessRestrictedException;
import org.qommons.misc.springdemo.repository.ResourceNotAccessibleException;
import org.qommons.misc.springdemo.repository.ScenarioRepository;
import org.qommons.misc.springdemo.repository.ScenarioSharingRepository;
import org.qommons.misc.springdemo.repository.UserScenarioView;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ScenarioService {
	private final ScenarioRepository theScenarioRepo;
	private final UserService theUserService;
	private final ScenarioDataService theScenarioData;
	private final ScenarioSharingRepository theScenarioSharingRepo;

	public ScenarioService(ScenarioRepository scenarioRepo, UserService userService, ScenarioDataService scenarioData,
		ScenarioSharingRepository scenarioSharingRepo) {
		theScenarioRepo = scenarioRepo;
		theUserService = userService;
		theScenarioData = scenarioData;
		theScenarioSharingRepo = scenarioSharingRepo;
	}

	public List<UserScenarioView> getAllAvailableScenarios(AuthenticatedUser user, Authentication auth) {
		Set<String> roles = UserService.getRoles(auth);
		System.out.println("User " + user.userName() + " has roles " + roles);
		Map<AvailableScenario, List<ScenarioSharing>> sharedScenarios = Arrays.asList(//
			roles.stream().flatMap(role -> theScenarioSharingRepo.getByRole(role).stream()))//
			.stream().flatMap(Function.identity())//
			.collect(Collectors.groupingBy(ScenarioSharing::getScenario));
		return Stream.concat(//
			theScenarioRepo.getScenariosByOwnerId(user.id()).stream().map(UserScenarioView::new), //
			sharedScenarios.entrySet().stream().map(entry -> new UserScenarioView(entry.getKey(), entry.getValue())))//
			.toList();
	}

	public AvailableScenario getScenario(long id, AuthenticatedUser user, Authentication auth) {
		Optional<AvailableScenario> scenario = theScenarioRepo.findById(id);
		if (scenario.isEmpty())
			throw new ResourceNotAccessibleException("scenario", String.valueOf(id));
		assertAccessible(user, auth, id, ResourceAccessType.View);
		return scenario.get();
	}

	public String getScenarioName(long scenarioId) {
		ScenarioData data = theScenarioData.getScenarioData(scenarioId);
		if (data == null)
			throw new ResourceNotAccessibleException("scenario", String.valueOf(scenarioId));
		return data.getName();
	}

	public void assertAccessible(AuthenticatedUser user, Authentication auth, long scenarioId, ResourceAccessType requiredAccess)
		throws ResourceNotAccessibleException {
		ResourceAccessType access = getAccessLevel(user, auth, scenarioId, requiredAccess);
		if (access == null)
			throw new ResourceNotAccessibleException("scenario", String.valueOf(scenarioId));
		else if (access.compareTo(requiredAccess) < 0)
			throw new ResourceAccessRestrictedException("scenario", String.valueOf(scenarioId), access, requiredAccess);
	}

	public ResourceAccessType getAccessLevel(AuthenticatedUser user, Authentication auth, long scenarioId) {
		return getAccessLevel(user, auth, scenarioId, ResourceAccessType.MAX);
	}

	private ResourceAccessType getAccessLevel(AuthenticatedUser user, Authentication auth, long scenarioId, ResourceAccessType cap) {
		if (theScenarioRepo.isScenarioOwnedBy(scenarioId, user.id()) > 0)
			return ResourceAccessType.Delete;
		ResourceAccessType[] allowedAccess = new ResourceAccessType[1];
		if (checkAccess(theScenarioSharingRepo.getAccessByUserAndScenario(user.id(), scenarioId), cap, allowedAccess))
			return allowedAccess[0];
		for (String role : UserService.getRoles(auth))
			if (checkAccess(theScenarioSharingRepo.getAccessByRoleAndScenario(role, scenarioId), cap, allowedAccess))
				return allowedAccess[0];
		return allowedAccess[0];
	}

	private static boolean checkAccess(Optional<ResourceAccessType> share, ResourceAccessType requiredAccess,
		ResourceAccessType[] prevAllowedAccess) {
		if (share.isEmpty())
			return false;
		else if (prevAllowedAccess[0] == null || share.get().compareTo(prevAllowedAccess[0]) > 0) {
			prevAllowedAccess[0] = share.get();
			if (prevAllowedAccess[0].compareTo(requiredAccess) >= 0)
				return true;
		}
		return false;
	}

	public AvailableScenario getDefaultScenario() {
		return null; // TODO
	}

	public void deleteScenarios(AuthenticatedUser user, Authentication auth, List<Long> scenarioIds) {
		for (Long scenarioId : scenarioIds)
			assertAccessible(user, auth, scenarioId, ResourceAccessType.Delete);
		for (Long scenarioId : scenarioIds) {
			theScenarioSharingRepo.deleteByScenarioId(scenarioId);
			theScenarioData.deleteScenario(scenarioId);
		}
	}

	@Transactional
	public AvailableScenario copyScenario(long sourceScenarioId, String newScenarioName, AuthenticatedUser user,
		Authentication auth) {
		Optional<AvailableScenario> scenario = theScenarioRepo.findById(sourceScenarioId);
		if (scenario.isEmpty())
			throw new ResourceNotAccessibleException("scenario", String.valueOf(sourceScenarioId));
		String userId = user.id();
		assertAccessible(user, auth, sourceScenarioId, ResourceAccessType.View);
		ScenarioData sourceData = theScenarioData.getScenarioData(sourceScenarioId);
		if (sourceData == null) {
			log.error("Data for scenario " + sourceScenarioId + " is missing. Cleaning up.");
			theScenarioRepo.delete(scenario.get());
			throw new ResourceNotAccessibleException("scenario", String.valueOf(sourceScenarioId));
		}
		DemoUser sageUser;
		AvailableScenario newScenario;
		try (Transaction _ = sourceData.lock(false); Transaction _ = theUserService.lockUser(userId)) {
			sageUser = theUserService.getOrCreateUser(user);

			for (AvailableScenario s : theScenarioRepo.getScenariosByOwnerId(sageUser.getId())) {
				ScenarioData data = theScenarioData.getScenarioData(s.getId());
				if (data != null && data.getName().equalsIgnoreCase(newScenarioName))
					throw new IllegalArgumentException("You already own a scenario named '" + newScenarioName + "'");
			}
			ScenarioData newScenarioData = theScenarioData.copyScenario(sourceData, newScenarioName);
			newScenario = new AvailableScenario(sageUser, newScenarioData.getId());
			theScenarioRepo.save(newScenario);
		}
		return newScenario;
	}
}
