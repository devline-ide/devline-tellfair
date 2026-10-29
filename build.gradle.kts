plugins { kotlin("jvm") }

dependencies {
    compileOnly(project(":devline-ide-extensions"))
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(project(":devline-ide-extensions"))
    testImplementation(project(":devline-ai-assistant"))
}
tasks.processResources { from("src/extension/extension.json") { rename { "tellfair-extension.json" } } }
val panelInstall = tasks.register<Exec>("panelInstall") {
    workingDir(projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "extension", projectDir.absolutePath)
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/extension-devkit"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/ui-web"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/animgraph"))
    outputs.dir(layout.projectDirectory.dir("node_modules/@devline/game-render-web"))
}
val panelBuild = tasks.register<Exec>("panelBuild") {
    dependsOn(panelInstall)
    workingDir(rootProject.projectDir)
    commandLine("node", rootProject.file("tools/extensions/frontend.mjs").absolutePath, "panel", projectDir.absolutePath)
    inputs.dir("web")
    inputs.files("package.json", "package-lock.json")
    inputs.dir("vendor")
    outputs.dir(layout.buildDirectory.dir("panel"))
}
val packPanel = tasks.register<Exec>("packPanel") {
    dependsOn(panelBuild)
    workingDir(rootProject.projectDir)
    commandLine("node", project.file("web/pack.mjs").absolutePath, layout.buildDirectory.dir("panel").get().asFile.absolutePath, layout.buildDirectory.dir("extension-web").get().asFile.absolutePath)
    inputs.dir(layout.buildDirectory.dir("panel")); outputs.dir(layout.buildDirectory.dir("extension-web"))
}
val extensionArchive = tasks.register<Zip>("extensionArchive") {
    group = "distribution"
    archiveFileName.set("devline-tellfair-0.1.10.dlextension")
    destinationDirectory.set(layout.buildDirectory.dir("extension"))
    from("src/extension/extension.json")
    from(packPanel) { into("web") }
    into("lib") { from(tasks.jar); from(configurations.runtimeClasspath) { exclude("kotlin-stdlib-*.jar", "annotations-*.jar", "error_prone_annotations-*.jar") } }
    isPreserveFileTimestamps = false; isReproducibleFileOrder = true
}
configurations.create("bundledExtension") { isCanBeConsumed = true; isCanBeResolved = false }
artifacts.add("bundledExtension", extensionArchive)
tasks.register<JavaExec>("tellfairAccountCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.tellfair.TellfairAccountCheckKt")
    args(layout.buildDirectory.dir("account-check").get().asFile.absolutePath)
}

tasks.register<JavaExec>("tellfairCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.tellfair.TellfairCheckKt")
}

tasks.register<JavaExec>("savedSpeechCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.tellfair.SavedSpeechCheckKt")
}

tasks.register<JavaExec>("agentSpeechCheck") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("devline.tellfair.AgentSpeechCheckKt")
}
