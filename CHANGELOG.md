# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- API
  - ASN.1 Types for `OTA_ChrgCtrlReq` and `OTA_ChrgCtrlStsResp`
- MQTT
  - support starting/stopping charging via setting `drivetrain/charging`

### Changed
- MQTT
  - **Breaking** The default refresh rate while the car is active has been changed to 30 seconds
  - **Breaking** The default refresh rate while the car is inactive has been changed to 24 hours
  - **Breaking** encode dates as unquoted ISO 8601 strings with offset and without timezone
  - support configuring `refresh/mode`, `refresh/period/active`, `refresh/period/inActive` and `refresh/period/inActiveGrace` via MQTT
  - Handle fallback for SOC when charge status update fails
  - ensure that a changed systemd configuration is picked up
- API
  - Handle fallback for SOC when charge status update fails

### Fixed
- MQTT
  - keep message fetch thread alive after connection failures
  - Make sure car state is updated after successful command

### Dependencies
- Bump `version.picocli` from 4.7.3 to 4.7.4 (#29)
- Bump `maven-failsafe-plugin` from 3.1.0 to 3.1.2 (#30)
- Bump `graal-sdk` from 22.3.2 to 23.0.0 (#33)
- Bump `native-maven-plugin` from 0.9.22 to 0.9.23 (#40)
- Bump `maven-shade-plugin` from 3.4.1 to 3.5.0 (#38)
- Bump `mockito-junit-jupiter` from 5.3.1 to 5.4.0 (#39)
- Bump `mockito-core` from 5.3.1 to 5.4.0 (#37)

## [0.2.1] - 2023-06-03
### Fixed
- MQTT
  - calculate correct tyre pressure

### Dependencies
- Bump `testcontainers-bom` from 1.18.1 to 1.18.3 (#27)
- Bump `maven-source-plugin` from 3.2.1 to 3.3.0 (#23)
- Bump `spotless-maven-plugin` from 2.36.0 to 2.37.0 (#24)
- Bump `jackson-dataformat-toml` from 2.15.1 to 2.15.2 (#25)

## [0.2.0] - 2023-04-02
### Changed
- extracted saic-ismart-client

### Fixed
- MQTT
  - log ABRP errors, don't fail the whole thread
  - keep last `drivetrain/hvBatteryActive` state until it's updated from the API
  - allow setting the `drivetrain/hvBatteryActive/set` state to force updates
  - forbid retained set messages
  - added topics `refresh/lastVehicleState` and `refresh/lastChargeState`

## [0.1.0] - 2023-03-29
### Added
- Initial support for SAIC API
- Initial HTTP Gateway
- Initial MQTT Gateway
  - Support for A Better Routeplaner Telemetry update
  - automatically register for all alarm types
  - create Docker image
  - create Debian package
  - support `climate/remoteClimateState/set` with `off`, `on` and `front`
  - support `doors/locked/set` with `true` and `false`

[Unreleased]: https://github.com/SAIC-iSmart-API/saic-java-client/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/SAIC-iSmart-API/saic-java-client/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/SAIC-iSmart-API/saic-java-client/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/SAIC-iSmart-API/saic-java-client/releases/tag/v0.1.0