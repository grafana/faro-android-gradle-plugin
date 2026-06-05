package com.grafana.faro

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.configuration.ConsoleOutput

/**
 * Gradle log lines aligned with `@grafana/faro-bundlers-shared` `consoleInfoOrange`:
 * `[Faro] …` in ANSI 256-color orange (214).
 */
internal object FaroLog {
    const val PREFIX = "[Faro] "

    private const val ORANGE = "\u001B[38;5;214m"
    private const val RESET = "\u001B[0m"

    fun lifecycle(logger: Logger, project: Project, message: String) {
        if (useColor(project)) {
            logger.lifecycle("$ORANGE$PREFIX$message$RESET")
        } else {
            logger.lifecycle("$PREFIX$message")
        }
    }

    fun warn(logger: Logger, message: String) {
        logger.warn("$PREFIX$message")
    }

    private fun useColor(project: Project): Boolean {
        if (!System.getenv("NO_COLOR").isNullOrBlank()) {
            return false
        }
        if (System.getenv("FORCE_COLOR") != null) {
            return true
        }
        return when (project.gradle.startParameter.consoleOutput) {
            ConsoleOutput.Rich -> true
            ConsoleOutput.Auto -> {
                val term = System.getenv("TERM")
                term != null && term.isNotBlank() && term != "dumb"
            }
            else -> false
        }
    }
}
