package com.focusflow.enforcement

import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessNameResolverTest {
    @Test
    fun usesProcessHandleAndFallsBackToQueryForElevatedProcesses() {
        val api = FakeProcessImageApi().apply {
            commandPaths[10L] = null
            queriedPaths[10L] = "C:\\Apps\\SecureApp.exe"
        }

        assertEquals("secureapp.exe", ProcessNameResolver(api).resolve(10L))
        assertEquals(listOf(10L), api.queryCalls)
    }

    @Test
    fun resolvesApplicationFrameHostToItsCoreWindowChild() {
        val api = FakeProcessImageApi().apply {
            commandPaths[20L] = "C:\\Windows\\System32\\ApplicationFrameHost.exe"
            commandPaths[21L] = "C:\\Program Files\\StoreApp\\StoreApp.exe"
            childPids[20L] = 21L
        }

        assertEquals(
            "storeapp.exe",
            ProcessNameResolver(api).resolve(20L, Pointer(123L))
        )
        assertEquals(20L, api.hostedCalls.single().first)
        assertEquals(Pointer(123L), api.hostedCalls.single().second)
    }

    @Test
    fun unresolvedStoreHostIsUnknownRatherThanCountedAsTheHost() {
        val warnings = mutableListOf<String>()
        val api = FakeProcessImageApi().apply {
            commandPaths[30L] = "ApplicationFrameHost.exe"
        }

        assertNull(ProcessNameResolver(api, warnings::add).resolve(30L, Pointer(321L)))
        kotlin.test.assertTrue(warnings.single().contains("Store app"))
    }

    private class FakeProcessImageApi : ProcessImageApi {
        val commandPaths = mutableMapOf<Long, String?>()
        val queriedPaths = mutableMapOf<Long, String?>()
        val childPids = mutableMapOf<Long, Long?>()
        val queryCalls = mutableListOf<Long>()
        val hostedCalls = mutableListOf<Pair<Long, Pointer?>>()

        override fun processHandleCommand(pid: Long) = commandPaths[pid]

        override fun queryFullImagePath(pid: Long): String? {
            queryCalls += pid
            return queriedPaths[pid]
        }

        override fun storeAppChildProcessId(hostPid: Long, foregroundWindow: Pointer?): Long? {
            hostedCalls += hostPid to foregroundWindow
            return childPids[hostPid]
        }
    }
}
