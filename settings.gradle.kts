dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
    }
}

rootProject.name = "mihon-desktop"

include(":platform-compat")
include(":source-api")
include(":extension-loader")
include(":app")
