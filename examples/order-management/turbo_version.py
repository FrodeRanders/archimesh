"""Reviewed immutable Turbo EA source pin for the optional local demo."""
VERSION = "2.203.2"
REVISION = "2223d50ef53f56ffe367e026c94ab01d70c263b2"
SOURCE_SHA256 = "c0b71ad3e2cff2678d2d623db94ca1fb253b5ef8c1c469f5e85ab7428ed5edc1"
PREVIOUS_VERSION = "2.194.0"


def deployed_version(state):
    # Runs created before version tracking used the original verified release.
    return state.get("turboVersion", PREVIOUS_VERSION)


def images():
    return {"DEMO_TURBO_IMAGE":"northstar-demo-turbo-backend:"+REVISION[:7],
            "DEMO_TURBO_DB_IMAGE":"northstar-demo-turbo-db:"+REVISION[:7],
            "DEMO_TURBO_UI_IMAGE":"northstar-demo-turbo-ui:"+REVISION[:7]}
