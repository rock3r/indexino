plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "indexino"

include(":indexino-bom")

include(":indexino-model")

include(":indexino-plugin-api")

include(":indexino-selection-context")

include(":indexino-script-host")

include(":detekt-plugin")
