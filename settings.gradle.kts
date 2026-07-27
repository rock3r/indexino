plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "indexino"

include(":indexino-model")

include(":indexino-plugin-api")

include(":indexino-selection-context")

include(":detekt-plugin")
