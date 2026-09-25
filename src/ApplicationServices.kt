package knitty

import io.ktor.client.HttpClient
import knitty.core.application.*
import knitty.core.model.GameId
import knitty.core.model.ProviderGameId
import knitty.core.model.ServerLayout
import knitty.core.ports.*
import knitty.filesystem.SftpFiles
import knitty.games.corekeeper.CoreKeeperInstallation
import knitty.games.corekeeper.CoreKeeperProfileDeployment
import knitty.profiles.FileProfileStore
import knitty.providers.modio.ModioProvider
import knitty.providers.thunderstore.ThunderstoreProvider
import knitty.servers.PelicanServerControl
import knitty.servers.SftpProfileDeployment
import knitty.settings.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.Path

internal class ApplicationServices(
    client: HttpClient,
    settingsFile: Path = localSettingsPath(),
) : AutoCloseable {
    private val container = koinApplication {
        modules(applicationModule(client, settingsFile), profileModule())
    }

    val configureModio: ConfigureModio = container.koin.get()
    val setupSteamLibrary: SetupSteamLibrary = container.koin.get()

    fun profile(directory: String, environment: ProfileEnvironment): ProfileServices {
        val context = ProfileContext(Path(directory).toAbsolutePath().normalize(), environment)
        val scope = container.koin.createScope<ProfileContext>(UUID.randomUUID().toString(), context)

        return ProfileServices(search = scope.get(), profiles = scope.get())
    }

    override fun close() = container.close()
}

internal data class ProfileServices(val search: SearchMods, val profiles: ManageProfile)

private data class ProfileContext(val directory: Path, val environment: ProfileEnvironment)

private fun applicationModule(client: HttpClient, settingsFile: Path) = module {
    single { client }
    single { ThunderstoreProvider(get()) }
    single<SteamLibrarySettings> { FileSteamLibrarySettings(settingsFile) }
    single<SaveModioAccess> { FileModioAccess(settingsFile.resolveSibling("modio.env")) }
    single<ValidateSteamLibrary> { SteamLibraryDirectory() }

    single<ConfigureModio> { DefaultConfigureModio(get()) }
    single<SetupSteamLibrary> { DefaultSetupSteamLibrary(get(), get()) }
}

private fun profileModule() = module {
    val games = mapOf(GameId("core-keeper") to ProviderGameId("corekeeper"))
    val layouts = mapOf(
        GameId("core-keeper") to ServerLayout(
            executable = "CoreKeeperServer",
            modsDirectory = "CoreKeeperServer_Data/StreamingAssets/Mods",
        ),
    )

    scope<ProfileContext> {
        scoped { requireNotNull(getSource<ProfileContext>()).environment }
        scoped {
            val environment = get<ProfileEnvironment>()
            ModioProvider(get(), environment["KNITTY_MODIO_API_KEY"], environment["KNITTY_MODIO_API_PATH"])
        }
        scoped<ProfileStore> { FileProfileStore(requireNotNull(getSource<ProfileContext>()).directory) }
        scoped<PlanInstall> { DefaultPlanInstall(get<ModioProvider>(), "modio", games) }
        scoped<LocateGameInstallation> {
            CoreKeeperInstallation(get<ProfileEnvironment>()["KNITTY_CORE_KEEPER_DIR"], settings = get())
        }
        scoped<ProfileDeployment> { CoreKeeperProfileDeployment() }
        scoped<ServerControl> { PelicanServerControl(get(), get<ProfileEnvironment>()::get) }
        scoped<ServerProfileDeployment> {
            SftpProfileDeployment(
                files = SftpFiles(get<ProfileEnvironment>()::get),
                layouts = layouts,
                locate = CoreKeeperInstallation(),
                deployment = get(),
            )
        }

        scoped<SearchMods> {
            ProviderSearchMods(
                mapOf(
                    GameId("core-keeper") to SearchSource(get<ModioProvider>(), ProviderGameId("corekeeper")),
                    GameId("valheim") to SearchSource(get<ThunderstoreProvider>(), ProviderGameId("valheim")),
                ),
            )
        }
        scoped<ManageProfile> {
            DefaultManageProfile(
                store = get(),
                planner = get(),
                locate = get(),
                deployment = get(),
                download = get<ModioProvider>(),
                games = games,
                serverDeployment = get(),
                serverControl = get(),
                newProfileId = { UUID.randomUUID().toString() },
            )
        }
    }
}
