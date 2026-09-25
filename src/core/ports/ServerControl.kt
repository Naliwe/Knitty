package knitty.core.ports

import knitty.core.model.ProfileResult
import knitty.core.model.ServerStatus
import knitty.core.model.ServerTarget

interface ServerControl {
    suspend fun inspect(target: ServerTarget): ProfileResult<ServerStatus>
    suspend fun stop(target: ServerTarget): ProfileResult<Unit>
    suspend fun start(target: ServerTarget): ProfileResult<Unit>
}
