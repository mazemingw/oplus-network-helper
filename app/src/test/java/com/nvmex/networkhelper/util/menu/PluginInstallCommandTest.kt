package com.nvmex.networkhelper.util.menu

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PluginInstallCommandTest {
    @Test fun stagesBeforeReplacementRestoresOwnershipAndKeepsBackup() {
        val target = "/data/user/0/com.oplus.engineernetwork/files/plugin-release.zip"
        val cmd = PluginInstallCommand.build("/data/user/0/com.nvmex.networkhelper/cache/plugin.zip", 123, target)
        assertTrue(cmd.contains(".nh-backup"))
        assertTrue(cmd.contains("stat -c '%u:%g'"))
        assertTrue(cmd.contains("chown \"\$nh_owner\""))
        assertFalse(cmd.contains("--reference"))
        assertTrue(cmd.contains("restorecon -F"))
        assertTrue(cmd.indexOf("-eq 123") < cmd.indexOf("mv -f"))
        assertFalse(cmd.contains("system_ext"))
        assertFalse(cmd.contains("rm -rf"))
    }
    @Test fun refusesWritesOutsideTheEngineeringPluginDirectory() {
        for (target in listOf("/system_ext/framework/plugin-release.zip", "/data/user/0/com.oplus.engineernetwork/files/../plugin-release.zip", "/data/user/0/another.app/files/plugin-release.zip")) {
            assertThrows(IllegalArgumentException::class.java) { PluginInstallCommand.build("/tmp/plugin", 123, target) }
        }
    }
    @Test fun createsIsolatedRealSampleInstallScriptsForAdbVerification() {
        val base = System.getenv("NH_PLUGIN_FIXTURE_DIR")
        org.junit.Assume.assumeTrue(base != null)
        for (name in listOf("new", "old")) {
            val extracted = File(base!!, "$name-plugin.zip")
            PluginArchive.prepare(File(base, "$name.zip"), extracted)
            File(base, "$name-install-verify.sh").writeText(PluginInstallCommand.build(
                "/data/local/tmp/nh-$name-plugin.zip", extracted.length(),
                "/data/user/0/com.oplus.engineernetwork/files/nh-install-verify/plugin-release.zip") + "\n")
        }
    }
}
