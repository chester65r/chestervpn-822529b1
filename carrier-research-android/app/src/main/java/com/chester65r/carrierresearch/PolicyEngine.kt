package com.chester65r.carrierresearch

enum class DataPlan {
    NO_ACTIVE_PLAN,
    SOCIAL_ONLY,
    GENERAL_DATA,
}

enum class TrafficClass {
    APPROVED_BUNDLE_APP,
    GENERAL_WEB,
    UNKNOWN,
    UNAUTHORIZED_RELAY,
}

enum class AddressFamily {
    IPV4,
    IPV6,
}

enum class PolicyVerdict {
    ALLOW_ZERO_RATED,
    ALLOW_GENERAL_DATA,
    DENY_NO_PLAN,
    DENY_OUT_OF_BUNDLE,
    DENY_UNCLASSIFIED,
    DENY_UNBOUND_SESSION,
    DENY_POLICY_GAP,
    DENY_UNAUTHORIZED_RELAY,
}

data class FlowRequest(
    val plan: DataPlan,
    val trafficClass: TrafficClass,
    val authenticatedSubscriber: Boolean = true,
    val boundToSubscriberSession: Boolean = true,
    val addressFamily: AddressFamily = AddressFamily.IPV4,
    val ipv6PolicyEnforced: Boolean = true,
    val relayExplicitlyAuthorized: Boolean = false,
)

data class PolicyTestResult(
    val name: String,
    val expected: PolicyVerdict,
    val actual: PolicyVerdict,
) {
    val passed: Boolean
        get() = expected == actual
}

object PolicyEngine {
    fun evaluate(request: FlowRequest): PolicyVerdict {
        if (!request.authenticatedSubscriber || !request.boundToSubscriberSession) {
            return PolicyVerdict.DENY_UNBOUND_SESSION
        }

        if (request.addressFamily == AddressFamily.IPV6 && !request.ipv6PolicyEnforced) {
            return PolicyVerdict.DENY_POLICY_GAP
        }

        if (
            request.trafficClass == TrafficClass.UNAUTHORIZED_RELAY &&
            !request.relayExplicitlyAuthorized
        ) {
            return PolicyVerdict.DENY_UNAUTHORIZED_RELAY
        }

        if (request.trafficClass == TrafficClass.UNKNOWN) {
            return PolicyVerdict.DENY_UNCLASSIFIED
        }

        if (request.plan == DataPlan.NO_ACTIVE_PLAN) {
            return PolicyVerdict.DENY_NO_PLAN
        }

        return when (request.plan) {
            DataPlan.NO_ACTIVE_PLAN -> PolicyVerdict.DENY_NO_PLAN
            DataPlan.SOCIAL_ONLY -> {
                if (request.trafficClass == TrafficClass.APPROVED_BUNDLE_APP) {
                    PolicyVerdict.ALLOW_ZERO_RATED
                } else {
                    PolicyVerdict.DENY_OUT_OF_BUNDLE
                }
            }
            DataPlan.GENERAL_DATA -> PolicyVerdict.ALLOW_GENERAL_DATA
        }
    }

    fun runDefensiveSuite(): List<PolicyTestResult> {
        val cases = listOf(
            Triple(
                "No package: general traffic is denied",
                FlowRequest(DataPlan.NO_ACTIVE_PLAN, TrafficClass.GENERAL_WEB),
                PolicyVerdict.DENY_NO_PLAN,
            ),
            Triple(
                "Social-only: approved app is accepted",
                FlowRequest(DataPlan.SOCIAL_ONLY, TrafficClass.APPROVED_BUNDLE_APP),
                PolicyVerdict.ALLOW_ZERO_RATED,
            ),
            Triple(
                "Social-only: general web is denied",
                FlowRequest(DataPlan.SOCIAL_ONLY, TrafficClass.GENERAL_WEB),
                PolicyVerdict.DENY_OUT_OF_BUNDLE,
            ),
            Triple(
                "Unbound subscriber session is denied",
                FlowRequest(
                    DataPlan.SOCIAL_ONLY,
                    TrafficClass.APPROVED_BUNDLE_APP,
                    boundToSubscriberSession = false,
                ),
                PolicyVerdict.DENY_UNBOUND_SESSION,
            ),
            Triple(
                "Missing IPv6 policy is fail-closed",
                FlowRequest(
                    DataPlan.GENERAL_DATA,
                    TrafficClass.GENERAL_WEB,
                    addressFamily = AddressFamily.IPV6,
                    ipv6PolicyEnforced = false,
                ),
                PolicyVerdict.DENY_POLICY_GAP,
            ),
            Triple(
                "Unauthorized relay is denied",
                FlowRequest(DataPlan.GENERAL_DATA, TrafficClass.UNAUTHORIZED_RELAY),
                PolicyVerdict.DENY_UNAUTHORIZED_RELAY,
            ),
            Triple(
                "General plan permits classified web traffic",
                FlowRequest(DataPlan.GENERAL_DATA, TrafficClass.GENERAL_WEB),
                PolicyVerdict.ALLOW_GENERAL_DATA,
            ),
        )

        return cases.map { (name, request, expected) ->
            PolicyTestResult(name, expected, evaluate(request))
        }
    }
}
