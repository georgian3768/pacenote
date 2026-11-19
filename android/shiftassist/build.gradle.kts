plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/**
 * Replay a telemetry JSONL capture (see tools/savvycan_to_jsonl.py) through the
 * shift assistant and print cue counts:
 *
 *   gradle :shiftassist:replay -Pjsonl=/path/to/replay_b.jsonl
 */
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replay a telemetry JSONL through ShiftAssistant"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pocketmechanic.shift.ReplayKt")
    args = buildList {
        add(project.findProperty("jsonl")?.toString() ?: "replay_b.jsonl")
        // Extra harness flags, e.g. -PreplayArgs="--scale 0.893 --redline 6100"
        project.findProperty("replayArgs")?.toString()
            ?.split(' ')?.filter { it.isNotBlank() }?.let(::addAll)
    }
}
