package org.qommons.misc.springdemo.controller;

import java.time.Instant;

import org.qommons.misc.springdemo.entities.AvailableScenario;
import org.qommons.misc.springdemo.entities.ResourceAccessType;
import org.qommons.misc.springdemo.entities.DemoUser;
import org.qommons.misc.springdemo.repository.UserScenarioView;
import org.qommons.misc.springdemo.service.AuthenticatedUser;
import org.qommons.misc.springdemo.service.ScenarioData;
import org.qommons.misc.springdemo.service.ScenarioDataService;
import org.qommons.misc.springdemo.service.ScenarioService;
import org.qommons.misc.springdemo.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/my-data")
public class DemoUserDataService {
	private UserService theUserSvc;
	private ScenarioService theScenarioSvc;
	private ScenarioDataService theScenarioDataSvc;

	DemoUserDataService(UserService userSvc, ScenarioService scenarioSvc, ScenarioDataService scenarioDataSvc) {
		theUserSvc = userSvc;
		theScenarioSvc = scenarioSvc;
		theScenarioDataSvc = scenarioDataSvc;
	}

	@GetMapping("/me")
	public ApiUser getMe(@AuthenticationPrincipal Jwt jwt) {
		AuthenticatedUser authUser = UserService.authenticated(jwt);
		return new ApiUser(authUser.id(), authUser.userName(), authUser.fullName(), Instant.now());
	}

	@GetMapping("/selected-scenario")
	public ApiScenario getSelectedScenario(@AuthenticationPrincipal Jwt jwt, Authentication auth) {
		AuthenticatedUser authUser = UserService.authenticated(jwt);
		DemoUser user = theUserSvc.getOrCreateUser(authUser);
		AvailableScenario scenario = user.getSelectedScenario();
		ScenarioData scenarioData = null;
		if (scenario != null && theScenarioSvc.getAccessLevel(authUser, auth, scenario.getId()) != null
			&& (scenarioData = theScenarioDataSvc.getScenarioData(scenario.getId())) != null) {
			// The selected scenario is accessible and has data
		} else {
			scenario = theScenarioSvc.getDefaultScenario();
			if (scenario != null && theScenarioSvc.getAccessLevel(authUser, auth, scenario.getId()) != null
				&& (scenarioData = theScenarioDataSvc.getScenarioData(scenario.getId())) != null) {
				// The default scenario is accessible and has data
			} else
				for (UserScenarioView s : theScenarioSvc.getAllAvailableScenarios(authUser, auth))
					if (theScenarioSvc.getAccessLevel(authUser, auth, s.scenario().getId()) != null
					&& (scenarioData = theScenarioDataSvc.getScenarioData(s.scenario().getId())) != null) {
						scenario = s.scenario();
						break;
					}
		}
		if (scenario == null)
			// The user cannot access any scenarios
			return null;
		else if (user.getSelectedScenario() == null || user.getSelectedScenario().getId() != scenario.getId()) {
			user.setSelectedScenario(scenario);
			theUserSvc.saveUser(user);
		}
		return ApiScenario.fromJPA(scenario, scenarioData.getName(),
			theScenarioSvc.getAccessLevel(authUser, auth, scenario.getId()).compareTo(ResourceAccessType.Delete) >= 0);
	}
}
