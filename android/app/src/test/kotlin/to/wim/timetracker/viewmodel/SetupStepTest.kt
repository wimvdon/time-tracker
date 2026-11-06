package to.wim.timetracker.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupStepTest {
    @Test
    fun `when not signed in the first step is signing in to Notion`() {
        assertEquals(SetupStep.SignIn, SetupStep.of(settingsLoaded = true, signedIn = false, workspaceCount = 0))
        assertEquals(SetupStep.SignIn, SetupStep.of(settingsLoaded = true, signedIn = false, workspaceCount = 2))
    }

    @Test
    fun `signed in but no workspace, the step is adding a workspace`() {
        assertEquals(SetupStep.Workspace, SetupStep.of(settingsLoaded = true, signedIn = true, workspaceCount = 0))
    }

    @Test
    fun `signed in with a workspace, nothing is left to set up`() {
        assertNull(SetupStep.of(settingsLoaded = true, signedIn = true, workspaceCount = 1))
    }

    @Test
    fun `nothing is shown while the settings are still loading`() {
        assertNull(SetupStep.of(settingsLoaded = false, signedIn = false, workspaceCount = 0))
    }
}
