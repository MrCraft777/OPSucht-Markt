plugins {
    id("net.labymod.labygradle")
    id("net.labymod.labygradle.addon")
}

val versions = providers.gradleProperty("net.labymod.minecraft-versions").get().split(";")

group = "net.craftportal"
version = providers.environmentVariable("VERSION").getOrElse("1.0.0")

labyMod {
    defaultPackageName = "net.craftportal"

    minecraft {
        registerVersion(versions.toTypedArray()) {
            runs {
                getByName("client") {
                }
            }
        }
    }

    tasks.jar {
        from("LICENSE.txt") {
            into("/")
        }
    }

    addonInfo {
        namespace = "opsuchtmarkt"
        displayName = "OPSucht Markt"
        author = "MrCraft777 (Loopnetmedia)"
        description = "Zeigt dir direkt den Preis des Items welches du in der Hand hältst über den OPMarkt. (Inoffizielles Addon)"
        minecraftVersion = "*"
        version = rootProject.version.toString()
    }
}

subprojects {
    plugins.apply("net.labymod.labygradle")
    plugins.apply("net.labymod.labygradle.addon")

    group = rootProject.group
    version = rootProject.version
}