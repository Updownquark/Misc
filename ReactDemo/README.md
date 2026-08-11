# Spring+React Demo Web Front-End

This project is the web page front-end of my little Spring+React demo web application.

## Setup

**IMPORTANT** This demo uses the Cesium JS map engine.  There are two important configuration actions that must be done for Cesium before the app will work:
 - Cesium resources, including script files, images, and other resources must be available to the browser.  There are several ways to accomplish this, but the easiest is to add a symbolic link in the public/ directory to `node_modules/cesium/Build/Cesium`.  You can also just copy the contents to public/, but make sure not to check them in!  A Windows shortcut DOES NOT WORK.
 - The Cesium Ion imagery services require an access token to work.  In the project's current state, it is not justified to purchase an enterprise license whose token could be checked in for re-use.  Each developer will need to go to ion.cesium.com, sign up for an account and log in, and in the "Access Tokens" tab, copy their token and paste it as the value of the `CESIUM_ACCESS_TOKEN` constant in `src/config/CesiumToken.ts`.  Don't check this in either.
