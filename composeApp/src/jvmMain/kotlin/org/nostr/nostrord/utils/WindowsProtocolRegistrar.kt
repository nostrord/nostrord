package org.nostr.nostrord.utils

/**
 * Registers the nostrord:// URI scheme on Windows by writing HKCU\Software\Classes\nostrord.
 *
 * jpackage/WiX has no URI-scheme option, so the app self-registers on every boot
 * (Discord/Slack do the same). HKCU needs no admin rights and matches the per-user
 * MSI install. Re-writing keeps the registration pointing at the current exe after
 * an upgrade moves the install path. No-op on other platforms and when running
 * unpackaged (gradle :composeApp:run launches java.exe, which must not own the scheme).
 */
object WindowsProtocolRegistrar {
    fun registerIfNeeded() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return
        if (exe.substringAfterLast('\\').equals("java.exe", ignoreCase = true) ||
            exe.substringAfterLast('\\').equals("javaw.exe", ignoreCase = true)
        ) {
            return
        }
        try {
            regAdd("HKCU\\Software\\Classes\\nostrord", "/ve", "/d", "URL:Nostrord Deep Link")
            // No /d: reg creates the empty REG_SZ the shell requires. A zero-length
            // ProcessBuilder arg is silently dropped on Windows (JDK-6518827), so an
            // explicit /d "" never reaches reg.exe.
            regAdd("HKCU\\Software\\Classes\\nostrord", "/v", "URL Protocol")
            // Inner quotes must arrive backslash-escaped: ProcessImpl's legacy encoder
            // passes an already-"quoted" arg verbatim and never escapes embedded quotes,
            // so reg.exe would tokenize "$exe" "%1" as two /d arguments and fail.
            regAdd("HKCU\\Software\\Classes\\nostrord\\DefaultIcon", "/ve", "/d", "\\\"$exe\\\",0")
            regAdd("HKCU\\Software\\Classes\\nostrord\\shell\\open\\command", "/ve", "/d", "\\\"$exe\\\" \\\"%1\\\"")
        } catch (e: Exception) {
            System.err.println("[nostrord] failed to register nostrord:// scheme: $e")
        }
    }

    private fun regAdd(vararg args: String) {
        val process =
            ProcessBuilder("reg", "add", *args, "/f")
                .redirectErrorStream(true)
                .start()
        process.inputStream.readAllBytes()
        if (process.waitFor() != 0) {
            error("reg add ${args.first()} exited ${process.exitValue()}")
        }
    }
}
