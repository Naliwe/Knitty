package knitty.core.application

import knitty.core.model.ModioAccess
import knitty.core.model.ModioAccessOutcome
import knitty.core.model.ModioSetupOutcome
import knitty.core.ports.SaveModioAccess

fun interface ConfigureModio {
    fun plan(apiPath: String, apiKey: String): ModioAccessOutcome = ModioAccess.parse(apiPath, apiKey)

    suspend fun save(access: ModioAccess): ModioSetupOutcome
}

class DefaultConfigureModio(private val settings: SaveModioAccess) : ConfigureModio {
    override suspend fun save(access: ModioAccess): ModioSetupOutcome = settings.save(access)
}
