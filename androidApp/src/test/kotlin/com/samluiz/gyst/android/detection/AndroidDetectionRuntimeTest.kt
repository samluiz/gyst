package com.samluiz.gyst.android.detection

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDetectionRuntimeTest {
    @After
    fun tearDown() {
        AndroidDetectionRuntime.resetForTests()
    }

    @Test
    fun `feature is disabled until process bindings are installed`() {
        assertFalse(AndroidDetectionRuntime.current().ingress.shouldCollect("com.example.bank"))
    }

    @Test
    fun `installed ingress is visible to framework entry points`() {
        val ingress =
            object : NotificationIngress {
                override fun shouldCollect(sourcePackage: String): Boolean = sourcePackage == "com.example.bank"

                override fun onPosted(envelope: AndroidNotificationEnvelope) = Unit

                override fun onRemoved(identity: AndroidNotificationIdentity) = Unit
            }

        AndroidDetectionRuntime.install(AndroidDetectionBindings(ingress = ingress))

        assertSame(ingress, AndroidDetectionRuntime.current().ingress)
        assertTrue(AndroidDetectionRuntime.current().ingress.shouldCollect("com.example.bank"))
    }
}
