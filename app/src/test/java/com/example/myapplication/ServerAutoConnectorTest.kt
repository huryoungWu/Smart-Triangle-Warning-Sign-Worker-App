package com.example.myapplication

import com.example.myapplication.core.data.net.ServerAutoConnector
import com.example.myapplication.core.data.net.ServerProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 启动期静默选服顺序：前面的能连就用前面的；前面的不通才用后面的；都不通返回 null。 */
class ServerAutoConnectorTest {

    @Test
    fun `前一个可用时不再探测后面的`() = runBlocking {
        val tried = mutableListOf<String>()
        val connector = ServerAutoConnector(listOf("primary", "backup")) { url ->
            tried += url
            ServerProbeResult.Ok(version = null)
        }

        assertEquals("primary", connector.select())
        assertEquals(listOf("primary"), tried)
    }

    @Test
    fun `前一个不可达时回落到后一个`() = runBlocking {
        val tried = mutableListOf<String>()
        val connector = ServerAutoConnector(listOf("primary", "backup")) { url ->
            tried += url
            if (url == "primary") {
                ServerProbeResult.Unreachable("timeout")
            } else {
                ServerProbeResult.Ok(version = null)
            }
        }

        assertEquals("backup", connector.select())
        assertEquals(listOf("primary", "backup"), tried)
    }

    @Test
    fun `全部不可达返回 null`() = runBlocking {
        val connector = ServerAutoConnector(listOf("primary", "backup")) {
            ServerProbeResult.Unreachable("timeout")
        }

        assertNull(connector.select())
    }

    @Test
    fun `有 HTTP 响应但接口不对不算可用`() = runBlocking {
        val connector = ServerAutoConnector(listOf("primary", "backup")) {
            ServerProbeResult.HttpError(404)
        }

        assertNull(connector.select())
    }
}
