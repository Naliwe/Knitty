package knitty.settings

import knitty.core.model.ModioAccess
import knitty.core.model.ModioSetupOutcome
import knitty.core.ports.SaveModioAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class FileModioAccess(private val file: Path) : SaveModioAccess {
    override suspend fun save(access: ModioAccess): ModioSetupOutcome = withContext(Dispatchers.IO) {
        var temporary: Path? = null
        try {
            file.parent.createDirectories()
            val attributes = if (Files.getFileAttributeView(file.parent, PosixFileAttributeView::class.java) != null) {
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                emptyArray()
            }
            temporary = Files.createTempFile(file.parent, ".modio-", ".tmp", *attributes)
            temporary.writeText("KNITTY_MODIO_API_PATH=${access.apiPath}\nKNITTY_MODIO_API_KEY=${access.apiKey}\n")
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            ModioSetupOutcome.Saved
        } catch (_: IOException) {
            ModioSetupOutcome.FilesystemFailure
        } finally {
            try {
                temporary?.deleteIfExists()
            } catch (_: IOException) {
                // Failed cleanup must not hide the outcome of the atomic credential replacement.
            }
        }
    }
}
