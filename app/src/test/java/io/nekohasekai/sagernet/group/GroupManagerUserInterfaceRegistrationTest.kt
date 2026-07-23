package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyGroup

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GroupManagerUserInterfaceRegistrationTest {
    @Test
    fun staleOwnerCannotUnregisterReplacement() {
        val oldInterface = FakeInterface()
        val newInterface = FakeInterface()

        GroupManager.registerUserInterface(oldInterface)
        GroupManager.registerUserInterface(newInterface)
        GroupManager.unregisterUserInterface(oldInterface)

        assertSame(newInterface, GroupManager.userInterface)
        GroupManager.unregisterUserInterface(newInterface)
    }

    @Test
    fun currentOwnerCanUnregisterItself() {
        val current = FakeInterface()

        GroupManager.registerUserInterface(current)
        GroupManager.unregisterUserInterface(current)

        assertNull(GroupManager.userInterface)
    }

    private class FakeInterface : GroupManager.Interface {
        override suspend fun confirm(message: String) = true
        override suspend fun alert(message: String) = Unit
        override suspend fun onUpdateStarted(group: ProxyGroup, byUser: Boolean) = Unit
        override suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean,
        ) = Unit

        override suspend fun onUpdateBusy(group: ProxyGroup, message: String) = Unit
        override suspend fun onUpdateFailure(group: ProxyGroup, message: String) = Unit
    }
}
