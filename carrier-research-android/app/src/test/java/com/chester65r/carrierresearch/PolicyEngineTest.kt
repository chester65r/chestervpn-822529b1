package com.chester65r.carrierresearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    @Test
    fun defensiveSuitePassesAllExpectedCases() {
        val results = PolicyEngine.runDefensiveSuite()
        assertEquals(9, results.size)
        assertTrue(results.all { it.passed })
    }

    @Test
    fun noPlanCannotAuthorizeGeneralTraffic() {
        assertEquals(
            PolicyVerdict.DENY_NO_PLAN,
            PolicyEngine.evaluate(
                FlowRequest(DataPlan.NO_ACTIVE_PLAN, TrafficClass.GENERAL_WEB)
            ),
        )
    }

    @Test
    fun socialOnlyPlanAllowsOnlyApprovedBundleClass() {
        assertEquals(
            PolicyVerdict.ALLOW_ZERO_RATED,
            PolicyEngine.evaluate(
                FlowRequest(DataPlan.SOCIAL_ONLY, TrafficClass.APPROVED_BUNDLE_APP)
            ),
        )
        assertEquals(
            PolicyVerdict.DENY_OUT_OF_BUNDLE,
            PolicyEngine.evaluate(
                FlowRequest(DataPlan.SOCIAL_ONLY, TrafficClass.GENERAL_WEB)
            ),
        )
    }

    @Test
    fun unauthenticatedAndUnboundSessionsFailClosed() {
        assertEquals(
            PolicyVerdict.DENY_UNBOUND_SESSION,
            PolicyEngine.evaluate(
                FlowRequest(
                    DataPlan.GENERAL_DATA,
                    TrafficClass.GENERAL_WEB,
                    authenticatedSubscriber = false,
                )
            ),
        )
        assertEquals(
            PolicyVerdict.DENY_UNBOUND_SESSION,
            PolicyEngine.evaluate(
                FlowRequest(
                    DataPlan.GENERAL_DATA,
                    TrafficClass.GENERAL_WEB,
                    boundToSubscriberSession = false,
                )
            ),
        )
    }

    @Test
    fun unknownTrafficFailsClosed() {
        assertEquals(
            PolicyVerdict.DENY_UNCLASSIFIED,
            PolicyEngine.evaluate(
                FlowRequest(DataPlan.GENERAL_DATA, TrafficClass.UNKNOWN)
            ),
        )
    }

    @Test
    fun ipv6PolicyGapFailsClosed() {
        assertEquals(
            PolicyVerdict.DENY_POLICY_GAP,
            PolicyEngine.evaluate(
                FlowRequest(
                    DataPlan.GENERAL_DATA,
                    TrafficClass.GENERAL_WEB,
                    addressFamily = AddressFamily.IPV6,
                    ipv6PolicyEnforced = false,
                )
            ),
        )
    }

    @Test
    fun unauthorizedRelayIsRejected() {
        assertEquals(
            PolicyVerdict.DENY_UNAUTHORIZED_RELAY,
            PolicyEngine.evaluate(
                FlowRequest(DataPlan.GENERAL_DATA, TrafficClass.UNAUTHORIZED_RELAY)
            ),
        )
    }
}
