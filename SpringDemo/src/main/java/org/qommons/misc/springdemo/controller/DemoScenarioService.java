package org.qommons.misc.springdemo.controller;

import java.util.List;
import java.util.Objects;

import org.qommons.misc.springdemo.entities.AvailableScenario;
import org.qommons.misc.springdemo.entities.ResourceAccessType;
import org.qommons.misc.springdemo.repository.ResourceNotAccessibleException;
import org.qommons.misc.springdemo.service.AuthenticatedUser;
import org.qommons.misc.springdemo.service.ScenarioService;
import org.qommons.misc.springdemo.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/scenarios")
public class DemoScenarioService {
	private final ScenarioService theScenarioService;

	public DemoScenarioService(ScenarioService scenarioService) {
		theScenarioService = scenarioService;
	}

	@GetMapping
	public List<ApiScenario> getScenarios(@AuthenticationPrincipal Jwt jwt, Authentication auth) {
		AuthenticatedUser user = UserService.authenticated(jwt);
		return theScenarioService.getAllAvailableScenarios(user, auth).stream()//
			.map(scenario -> toApi(scenario.scenario(), user, auth))//
			.filter(Objects::nonNull)//
			.sorted(ApiScenario::compareTo)//
			.toList();
	}

	@GetMapping("/{id}")
	public ApiScenario getScenario(@AuthenticationPrincipal Jwt jwt, Authentication auth, @PathVariable long id) {
		AuthenticatedUser user = UserService.authenticated(jwt);
		AvailableScenario scenario = theScenarioService.getScenario(id, user, auth);
		ApiScenario api = toApi(scenario, user, auth);
		if (api == null)
			throw new ResourceNotAccessibleException("scenario", String.valueOf(id));
		return api;
	}

	@PostMapping(path = "/copy")
	@Transactional
	public ApiScenario copyScenario(@AuthenticationPrincipal Jwt jwt, Authentication auth, @RequestBody ScenarioCopyCommand command) {
		AuthenticatedUser user = UserService.authenticated(jwt);
		AvailableScenario newScenario = theScenarioService.copyScenario(command.sourceScenarioId(), command.name(), user, auth);
		return new ApiScenario(newScenario.getId(), command.name(), ApiUser.fromJPA(newScenario.getOwner()), true);
	}

	@DeleteMapping("/{ids}")
	@Transactional
	public void deleteScenario(@AuthenticationPrincipal Jwt jwt, Authentication auth, @PathVariable List<Long> ids) {
		theScenarioService.deleteScenarios(UserService.authenticated(jwt), auth, ids);
	}

	private ApiScenario toApi(AvailableScenario scenario, AuthenticatedUser user, Authentication auth) {
		return ApiScenario.fromJPA(scenario, theScenarioService.getScenarioName(scenario.getId()),
			theScenarioService.getAccessLevel(user, auth, scenario.getId()).compareTo(ResourceAccessType.Delete) >= 0);
	}
}
