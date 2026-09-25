package knitty

import knitty.core.model.*

internal val sampleServer = ServerTarget(
    "ovh",
    "https://panel.example.com",
    "12345678-1234-1234-1234-123456789abc",
    SftpEndpoint("sftp.example.com", 2022, "player.12345678", "SHA256:" + "A".repeat(43)),
)

internal val sampleServerSync = sampleSync.copy(
    source = sampleSync.source.copy(profile = sampleProfile.copy(servers = listOf(sampleServer))),
    server = ServerSync(sampleServer, ServerStatus(ServerPowerState.Running, true)),
)
