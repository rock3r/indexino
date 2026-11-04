package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.engine.BuildExitCodes

internal object CliExitCodes {
    const val SUCCESS = BuildExitCodes.SUCCESS
    const val ANALYSIS_ERROR = 1
    const val INVALID_ARGUMENTS = BuildExitCodes.INVALID_ARGUMENTS
    const val TOPOLOGY_FAILED = BuildExitCodes.TOPOLOGY_FAILED
}
