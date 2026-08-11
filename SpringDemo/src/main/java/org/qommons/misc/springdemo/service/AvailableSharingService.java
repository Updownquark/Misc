package org.qommons.misc.springdemo.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AvailableSharingService {
	public static final String ROLE_PREFIX = "ROLE_";
	public static final String APP_ROLE_PREFIX = "DEMO_";
	private static final long SYNC_FREQUENCY = 10_000;

	@Value("${demoData.auth.admin_server}")
	private String authServer;
	@Value("${demoData.auth.client_name}")
	private String clientName;
	private String clientId;

	private WebClient theWebClient;
	private final AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager;
	private OAuth2AuthorizeRequest theAuthRequest;

	private NavigableSet<AuthenticatedUser> theAvailableUsers = Collections.emptyNavigableSet();
	private long theUsersLastSynced;

	private NavigableSet<String> theAvailableRoles;
	private long theRolesLastSynced;

	AvailableSharingService(AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager) {
		this.authorizedClientManager = authorizedClientManager;
	}

	@PostConstruct
	private void init() {
		theWebClient = WebClient.builder()//
			.baseUrl(authServer)//
			.build();
		theAuthRequest = OAuth2AuthorizeRequest.withClientRegistrationId("spring-demo")//
			.principal("auth-data") // Internal app identifier (from application.yaml)
			// string
			.build();

		List<Map<String, Object>> clients = callAdminServer("/clients?clientId=" + clientName,
			new ParameterizedTypeReference<List<Map<String, Object>>>() {});
		if (clients == null) { // Could not call admin client. Error already logged
		} else if (clients.isEmpty())
			log.error("No such client '" + clientName + "'");
		else
			clientId = (String) clients.get(0).get("id");
	}

	protected <T> T callAdminServer(String relativeUri, ParameterizedTypeReference<T> type) {
		// 2. This line FORCE-TRIGGERS the POST token request to Keycloak
		OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(theAuthRequest);
		if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
			log.error("Failed to acquire authorization token from admin server");
			return null;
		}

		String tokenValue = authorizedClient.getAccessToken().getTokenValue();

		// 3. Make the API request with the token directly in the header
		return theWebClient.get().uri(relativeUri).headers(headers -> headers.setBearerAuth(tokenValue)) // Manual injection
			.retrieve()//
			.bodyToMono(type)//
			.block();
	}

	public NavigableSet<AuthenticatedUser> getAvailableVistaUsers() {
		long now = System.currentTimeMillis();
		if (now - theUsersLastSynced > SYNC_FREQUENCY) {
			String result = theWebClient.get().uri("/users")//
				.attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId("spring-demo"))
				.retrieve()//
				.bodyToMono(String.class)//
				.block();
			System.out.println("Users: " + result);
			// TODO update the users
		}
		return theAvailableUsers;
	}

	public NavigableSet<String> getAvailableVistaRoles() {
		long now = System.currentTimeMillis();
		if (now - theRolesLastSynced > SYNC_FREQUENCY) {
			String result = theWebClient.get().uri("/roles")//
				.attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId("spring-demo"))
				.retrieve()//
				.bodyToMono(String.class)//
				.block();
			System.out.println("Realm Roles: " + result);
			if (clientId != null) {
				result = theWebClient.get().uri("/clients/" + clientId + "/roles")//
					.attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId("spring-demo"))
					.retrieve()//
					.bodyToMono(String.class)//
					.block();
				System.out.println("Client Roles: " + result);
			}
			// TODO update the roles
		}
		return theAvailableRoles;
	}

}
