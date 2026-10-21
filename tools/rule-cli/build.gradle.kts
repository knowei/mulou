plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:rules"))
}

application {
    mainClass.set("com.dmanime.tools.RuleCliKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}
