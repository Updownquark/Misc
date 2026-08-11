# Spring+React Demo Web Backend

This file is intended to describe this project (housed in this file's parent folder) to a developer.

This project is intended as the server backend for a simple demo web application built with Spring and React.

# High-Level Architecture

This project was created using Spring Boot's initializer (start.spring.io).  It uses:
 - Java version 25.
 - Maven
 - OAuth2 Authentication and Authorization
 - Standard Spring MVC web architecture (Model, View, Controller, Service, Repository, etc.)
 - JPA to access a relational database


# Setup

## Runtime Dependencies
It has the following external runtime dependencies:
 - An OAuth2 authentication server.  Currently, this must be KeyCloak.  This is because I have code that calls KeyCloak's admin service to discover users and roles.  I don't believe there's a standard way to do this.  In the future, I'd like to enhance the application such that if the admin service is unavailable (e.g. because the authentication server is not KeyCloak), the application still runs with no errors, though users and roles will not be discoverable, so the user would have to fat-finger role and user names when sharing scenarios.
 - A JDBC-enabled database.  I'm currently using PostgreSQL.  This is configured in the ".env" properties file under the section *Users and Scenarios database configuration*

The best way to run these dependencies is to install Docker Desktop, create a terminal at the root of this project, and execute `docker compose up -d`.  The compose.yaml file tells docker how to configure and install these dependencies and stand them up such that the web backend (and front end, for some) can access them.  If you want to use another way to stand up these dependencies, you're on your own.

### KeyCloak Configuration
To configure KeyCloak to be ready for the application to access it, log in to the administrator console (by default, http://localhost:8081) with the user name and password you'll find in the .env file.

The demo app relies on the authentication server to tell it what roles are assigned to the current user.  To make KeyClock do this:
 - Under **Client scopes**, click *Create client scope*.  Enter:
    - *Name*: *Groups*
    - *Decription*: *Causes KeyCloak roles to be shared with the client in the OpenID standard "groups" key*
    - *Type*: *Default*
    - Click *Save*
 - Select the new *Groups* client scope.  Select the **Mappers** tab.  Click *Add mapper* and select *By configuration*.  Select *User Realm Role*.  Enter:
     - *Name*: *Realm Roles to Groups Claim*
     - *Realm Role prefix*: *ROLE_*
     - *Token Claim Name*: *groups*
     - Click *Save*

Now we need to add the clients for the front-end (web app) and the back-end (enables listing of users and roles, though this UI feature is not yet implemented).

 - Under **Clients**, click *Create client*.  For *Client ID*, enter *react-demo*.  The name and description can be anything. Click *Next*.
 - Select *Standard flow*, * Direct access grants*, and *OAuth 2.0 Device Authorization Grant*
 - Click *Save*
 - Select the new *react-demo* client. Enter:
     - *Root URL*: *http://localhost:8080*
     - *Home URL*: *http://localhost:8080*
     - *Valid redirect URIs*: *http://localhost:8080/login/oauth2/code/auth-server*, *http://localhost:5173*, *http://localhost:5173/*
     - *Web origins*: *http://localhost:5173*
     - *Admin URL*: *http://localhost:8080*
     - Under *Client scopes*, click *Add client scope*.  Check *Groups*.  Click *Add* and select *Default*.
     - Under *Settings*, click *Save*
 - Click *Create client* again.  For *Client ID*, enter *spring-demo*.  The name and description can be anything.  Click *Next*.
 - Select *Client authentication*, *Standard flow*, and *Service account roles*.
 - Click *Save*
 - Select the new *spring-demo* client.
 - Under *Credentials*, enter:
      - *Client Authenticator*: *Client Id and Secret*
      - *Allowed authentication method*: *Any*
      - Next to *Client Secret*, click the copy button. Open /src/main/resources/application.yaml.  Replace *<OAUTH Server Client Secret>* with the pasted client secret.
      - Under *Service account roles*, click *Assign role* and select *Client roles*. Check all the following (you'll need to click the arrows to scroll through all the role pages): *query-clients*, *query-users*, *view-clients*, *view-realm*, and *view-users*.  Click *Assign*.
      - Under *Settings*, click *Save*

Now create user roles:
 - Click **Realm Roles**.  Click *Create role*.  Enter:
      - *Role name*: *DEMO_User*
      - *Description*: *A user that can access the demo application, view canned scenarios, and create their own scenarios*
      - Click *Save*
 - Click *Create role* again.  Enter:
      - *Role name*: *DEMO_Admin*
      - *Description*: *A user that can see all scenarios, regardless of who owns them or who they are shared with*
      - Click *Save*

Now create the user role and assign roles:
 - Click **Users**.  Select *admin*.  Select *Role mapping*.  Click *Assign role* and select *Realm roles*.  Check *DEMO_Admin* and click *Assign*.
 - Click back to **Users** and click *Add user*.  Enter *demo* for the *Username*.  The rest can be anything.  Click *Create*.
 - Select the *demo* user and assign it the *DEMO_User* role similar to how the DEMO_Admin role was assigned to admin.

# Application Architecture

The DemoScenarioService ("/scenarios" request mapping) exposes scenarios to the front-end (either the app web page in ../ReactDemo or some external web client not yet written).  These scenarios consist of an ID, an owner user, and a name.  That's it.  The stuff that may one day make a scenario interesting is in DemoUserDataService ("/scenario-data").  This service is just a placeholder at the moment.

The "database" does not store any scenario information beyond the barebones info exposed via the "/scenario" service, but only stores information about users and which scenarios they can access.  There is a mechanism for users to share their scenarios with other users, either by name or by assigned role.

The idea is that an application will log in as a user, and the backend will use the user's name to look up a parallel user in its database, which will allow it to determine which scenarios the user can see, modify, and/or delete.

The web client can then decide which scenario to operate with.  The backend will not enforce the user's scenario selection (though it will track it simply for restoring the user's selected scenario next time they log in), enabling a user to run 2 different clients running 2 different scenarios at the same time.

That's pretty much all that I've designed so far.  There's a barebones web client I've started writing with React and Material.  So far it just logs in, displays the scenario and allows the user to select a different one, and has a small management UI allowing the user to delete scenarios they're allowed to.

So now I'll get to work.
