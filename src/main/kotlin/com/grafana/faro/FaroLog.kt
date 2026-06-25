package com.grafana.faro

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.configuration.ConsoleOutput

/**
 * Gradle `[Faro]` log lines: orange for progress, bold green for completion (Gradle BUILD SUCCESSFUL style).
 */
internal object FaroLog {
    const val PREFIX = "[Faro] "

    /** Progress / in-flight messages — aligned with `@grafana/faro-bundlers-shared` orange (214). */
    private const val ORANGE = "\u001B[38;5;214m"

    private const val SUCCESS_GREEN = "\u001B[1;32m"
    private const val RED = "\u001B[38;5;196m"
    private const val RESET = "\u001B[0m"

    fun info(logger: Logger, project: Project, message: String) {
        if (useColor(project)) {
            logger.lifecycle("$ORANGE$PREFIX$message$RESET")
        } else {
            logger.lifecycle("$PREFIX$message")
        }
    }

    fun success(logger: Logger, project: Project, message: String) {
        if (useColor(project)) {
            logger.lifecycle("$SUCCESS_GREEN$PREFIX$message$RESET")
        } else {
            logger.lifecycle("$PREFIX$message")
        }
    }

    fun warn(logger: Logger, message: String) {
        logger.warn("$PREFIX$message")
    }

    fun error(logger: Logger, project: Project, message: String) {
        if (useColor(project)) {
            logger.error("$RED$PREFIX$message$RESET")
        } else {
            logger.error("$PREFIX$message")
        }
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
